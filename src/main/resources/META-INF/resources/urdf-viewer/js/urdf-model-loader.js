import * as THREE from "three";
import URDFLoader from "urdf-loader";
import { STLLoader } from "three/addons/loaders/STLLoader.js";

const DEFAULT_LIMITS = Object.freeze({
    maxMeshes: 300,
    maxStlBytes: 256 * 1024 * 1024,
    maxTriangles: 5_000_000
});
const immutableAssetBuffers = new Map();

export function createUrdfRobotRenderer(definition, onStatus = () => {}, configuredLimits = {}) {
    const limits = { ...DEFAULT_LIMITS, ...configuredLimits };
    const abortController = new AbortController();
    const manager = new THREE.LoadingManager();
    // One STL may be referenced by several links/visuals. Share its parsed geometry so
    // safety accounting matches the importer's unique-file accounting and the browser
    // does not download, parse, or retain duplicate vertex buffers.
    const geometryPromises = new Map();
    const geometries = new Set();
    const materials = new Set();
    const root = new THREE.Group();
    const startedAt = performance.now();
    let robot = null;
    let disposed = false;
    let meshRequests = 0;
    let meshLoads = 0;
    let stlBytes = 0;
    let triangles = 0;
    let failedUrl = null;
    let loadFailure = null;

    root.name = "urdf-visual-root";
    applyRootTransform(root, definition.rootTransform);

    const ready = new Promise((resolve, reject) => {
        manager.onProgress = () => report("loading");
        manager.onError = url => {
            failedUrl ||= displayUrl(url);
        };
        manager.onLoad = () => {
            if (disposed) {
                reject(new DOMException("URDF load cancelled", "AbortError"));
                return;
            }
            if (!robot || loadFailure || failedUrl || meshLoads !== meshRequests) {
                reject(loadFailure || new Error(failedUrl
                    ? `Failed to load model asset ${failedUrl}`
                    : `URDF model completed with ${meshLoads}/${meshRequests} meshes`));
                return;
            }
            try {
                validateMappedJoints(robot, definition.joints);
                root.add(robot);
                robot.updateMatrixWorld(true);
                report("urdf");
                resolve(api());
            } catch (error) {
                reject(error);
            }
        };

        const loader = new URDFLoader(manager);
        loader.packages = definition.packages || {};
        loader.loadMeshCb = (path, loadingManager, material, done) => {
            let safeUrl;
            try {
                safeUrl = safeMeshUrl(path, definition);
                meshRequests++;
                if (meshRequests > limits.maxMeshes) {
                    throw new Error(`URDF exceeds the ${limits.maxMeshes} mesh safety limit`);
                }
                report("loading");
            } catch (error) {
                failedUrl ||= displayUrl(path);
                loadFailure ||= error;
                done(null, error);
                return;
            }

            const itemUrl = safeUrl.href;
            loadingManager.itemStart(itemUrl);
            loadGeometry(itemUrl)
                    .then(geometry => {
                        materials.add(material);
                        const mesh = new THREE.Mesh(geometry, material);
                        mesh.castShadow = false;
                        mesh.receiveShadow = false;
                        meshLoads++;
                        done(mesh);
                        report("loading");
                    })
                    .catch(error => {
                        if (error.name !== "AbortError") {
                            failedUrl ||= displayUrl(itemUrl);
                            loadFailure ||= error;
                            loadingManager.itemError(itemUrl);
                        }
                        done(null, error);
                    })
                    .finally(() => loadingManager.itemEnd(itemUrl));
        };
        loader.load(definition.urdfUrl, loadedRobot => {
            robot = loadedRobot;
        }, undefined, error => {
            failedUrl ||= displayUrl(definition.urdfUrl);
            reject(error);
        });
    }).catch(error => {
        if (!disposed) {
            report("error", error.message);
        }
        dispose();
        throw error;
    });

    return { ready, dispose };

    function loadGeometry(itemUrl) {
        const existing = geometryPromises.get(itemUrl);
        if (existing) {
            return existing;
        }
        const loading = fetchImmutableAsset(itemUrl, definition.assetRevision, abortController.signal)
                .then(buffer => {
                    if (disposed) {
                        throw new DOMException("URDF load cancelled", "AbortError");
                    }
                    stlBytes += buffer.byteLength;
                    if (stlBytes > limits.maxStlBytes) {
                        throw new Error(`URDF exceeds the ${formatBytes(limits.maxStlBytes)} STL safety limit`);
                    }
                    const geometry = new STLLoader().parse(buffer);
                    if (!geometry.getAttribute("normal")) {
                        geometry.computeVertexNormals();
                    }
                    const meshTriangles = geometry.index
                        ? geometry.index.count / 3
                        : geometry.getAttribute("position").count / 3;
                    triangles += meshTriangles;
                    if (triangles > limits.maxTriangles) {
                        geometry.dispose();
                        throw new Error(`URDF exceeds the ${limits.maxTriangles.toLocaleString()} triangle safety limit`);
                    }
                    geometries.add(geometry);
                    return geometry;
                })
                .catch(error => {
                    geometryPromises.delete(itemUrl);
                    throw error;
                });
        geometryPromises.set(itemUrl, loading);
        return loading;
    }

    function api() {
        return {
            root,
            applyPose,
            setJointValue,
            resetPose,
            jointDefinitions,
            bounds: () => new THREE.Box3().setFromObject(root),
            metrics,
            dispose
        };
    }

    function applyPose(logicalJointAngles) {
        if (!robot || disposed) {
            return;
        }
        Object.entries(definition.joints || {}).forEach(([logicalJoint, mapping]) => {
            const degrees = logicalJointAngles[logicalJoint];
            if (!Number.isFinite(degrees)) {
                return;
            }
            robot.setJointValue(mapping.urdfJoint,
                    mapping.zeroOffsetRadians + mapping.direction * THREE.MathUtils.degToRad(degrees));
        });
    }

    function setJointValue(jointName, radians) {
        if (!robot || disposed || !robot.joints?.[jointName] || !Number.isFinite(radians)) {
            return false;
        }
        robot.setJointValue(jointName, radians);
        robot.updateMatrixWorld(true);
        return true;
    }

    function resetPose() {
        jointDefinitions().forEach(joint => setJointValue(joint.name, 0));
    }

    function jointDefinitions() {
        if (!robot || disposed) {
            return [];
        }
        return Object.entries(robot.joints || {})
                .map(([name, joint]) => {
                    const type = joint.jointType || "unknown";
                    const continuous = type === "continuous";
                    return {
                        name,
                        type,
                        // urdf-loader represents omitted limits as 0..0. Continuous joints do not
                        // have positional limits, so expose one full turn as a practical UI range.
                        lower: continuous ? -Math.PI : finiteOrNull(joint.limit?.lower),
                        upper: continuous ? Math.PI : finiteOrNull(joint.limit?.upper)
                    };
                })
                .filter(joint => ["revolute", "continuous", "prismatic"].includes(joint.type))
                .sort((left, right) => left.name.localeCompare(right.name));
    }

    function metrics() {
        return {
            status: "urdf",
            loadedAssets: meshLoads,
            totalAssets: meshRequests,
            triangles,
            drawCalls: meshLoads,
            bytes: stlBytes,
            loadMs: performance.now() - startedAt,
            error: null
        };
    }

    function report(status, error = null) {
        onStatus({
            status,
            loadedAssets: meshLoads,
            totalAssets: meshRequests,
            triangles,
            drawCalls: meshLoads,
            bytes: stlBytes,
            loadMs: status === "loading" ? null : performance.now() - startedAt,
            error
        });
    }

    function dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        abortController.abort();
        root.removeFromParent();
        geometries.forEach(geometry => geometry.dispose());
        materials.forEach(material => material?.dispose());
        geometries.clear();
        materials.clear();
    }
}

function fetchImmutableAsset(url, revision = "bundled", signal) {
    const key = `${revision || "bundled"}:${url}`;
    const cached = immutableAssetBuffers.get(key);
    if (cached) return Promise.resolve(cached);
    return fetch(url, { signal, credentials: "same-origin" })
            .then(response => {
                if (!response.ok) throw new Error(`HTTP ${response.status} while loading ${displayUrl(url)}`);
                return response.arrayBuffer();
            })
            .then(buffer => {
                immutableAssetBuffers.set(key, buffer);
                return buffer;
            });
}

export function clearUrdfAssetCache() {
    immutableAssetBuffers.clear();
}

function validateMappedJoints(robot, mappings = {}) {
    const missing = Object.values(mappings)
            .map(mapping => mapping.urdfJoint)
            .filter(jointName => !robot.joints?.[jointName]);
    if (missing.length > 0) {
        throw new Error(`URDF is missing mapped joint(s): ${missing.join(", ")}`);
    }
}

function applyRootTransform(root, transform = {}) {
    const translation = transform.translationMeters || [0, 0, 0];
    const rotation = transform.rotationRpyDegrees || [0, 0, 0];
    root.position.fromArray(translation);
    root.rotation.set(...rotation.map(THREE.MathUtils.degToRad));
    root.scale.setScalar(transform.scale ?? 1);
}

function safeMeshUrl(path, definition) {
    const urdf = new URL(definition.urdfUrl, window.location.href);
    const allowedRoots = [
        definition.assetRootUrl ? new URL(definition.assetRootUrl, window.location.href) : new URL("./", urdf),
        ...Object.values(definition.packages || {})
                .map(packagePath => new URL(`${packagePath.replace(/\/$/, "")}/`, window.location.href))
    ];
    const url = new URL(path, urdf);
    let decodedPath;
    try {
        decodedPath = decodeURIComponent(url.pathname);
    } catch (_error) {
        throw new Error(`Unsafe or unsupported URDF mesh URL: ${displayUrl(path)}`);
    }
    const unsafeSegments = decodedPath.split("/").some(segment => segment === ".." || segment === ".");
    if (url.origin !== window.location.origin || !decodedPath.toLowerCase().endsWith(".stl")
            || decodedPath.includes("\\") || decodedPath.includes("\0") || unsafeSegments
            || !allowedRoots.some(root => root.origin === url.origin && url.pathname.startsWith(root.pathname))) {
        throw new Error(`Unsafe or unsupported URDF mesh URL: ${displayUrl(path)}`);
    }
    return url;
}

function finiteOrNull(value) {
    return Number.isFinite(value) ? value : null;
}

function formatBytes(bytes) {
    return `${Math.round(bytes / (1024 * 1024))} MiB`;
}

function displayUrl(value) {
    try {
        const url = new URL(value, window.location.href);
        return url.origin === window.location.origin ? url.pathname : url.href;
    } catch (_error) {
        return String(value);
    }
}
