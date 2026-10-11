import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { createUrdfRobotRenderer } from "./urdf-model-loader.js?v=20261009-large-model-v11";

export function createUrdfSceneViewer(options) {
    const container = options.container;
    const loadingElement = options.loadingElement;
    const loadingTextElement = options.loadingTextElement;
    const metricsElement = options.metricsElement;
    const jointsElement = options.jointsElement;
    const onStatus = options.onStatus || (() => {});
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, 1, 0.005, 100);
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
    const controls = new OrbitControls(camera, renderer.domElement);
    const modelGroup = new THREE.Group();
    let pendingLoader = null;
    let activeModel = null;
    let generation = 0;
    let renderRequested = true;
    let disposed = false;

    scene.background = new THREE.Color(0xf8fafc);
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    container.replaceChildren(renderer.domElement);

    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.addEventListener("change", requestRender);

    scene.add(modelGroup);
    scene.add(new THREE.HemisphereLight(0xffffff, 0x475569, 2.4));
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.5);
    keyLight.position.set(2, 3, 2);
    scene.add(keyLight);
    scene.add(new THREE.GridHelper(10, 100, 0x0f766e, 0xcbd5e1));
    scene.add(new THREE.AxesHelper(0.3));

    camera.position.set(1.4, 0.9, 1.2);
    controls.target.set(0, 0.2, 0);
    controls.update();

    const resizeObserver = new ResizeObserver(entries => {
        const rect = entries[0].contentRect;
        resize(Math.max(320, Math.floor(rect.width)), Math.max(320, Math.floor(rect.height)));
    });
    resizeObserver.observe(container);
    resize(Math.max(320, container.clientWidth), Math.max(320, container.clientHeight));
    window.requestAnimationFrame(animationFrame);

    return {
        load,
        clear,
        resetView,
        resetPose,
        rotateLeft: () => rotate(-Math.PI / 9),
        rotateRight: () => rotate(Math.PI / 9),
        dispose
    };

    async function load(definition) {
        const loadGeneration = ++generation;
        pendingLoader?.dispose();
        pendingLoader = null;
        activeModel?.dispose();
        activeModel = null;
        modelGroup.clear();
        jointsElement?.replaceChildren();
        showLoading("Reading URDF…");
        updateMetrics(null);
        requestRender();

        const loader = createUrdfRobotRenderer(definition, status => {
            if (loadGeneration !== generation) {
                return;
            }
            showLoading(progressText(status));
            updateMetrics(status);
            onStatus(status);
        }, definition.limits || {});
        pendingLoader = loader;
        try {
            const loaded = await loader.ready;
            if (loadGeneration !== generation) {
                loaded.dispose();
                return;
            }
            pendingLoader = null;
            activeModel = loaded;
            modelGroup.add(loaded.root);
            createJointControls(loaded);
            fitCamera(loaded.bounds());
            updateMetrics(loaded.metrics());
            hideLoading();
            onStatus(loaded.metrics());
            requestRender();
        } catch (error) {
            if (loadGeneration !== generation || error.name === "AbortError") {
                return;
            }
            pendingLoader = null;
            showLoading(`Could not load model: ${error.message}`, true);
            updateMetrics({ status: "error", error: error.message });
            onStatus({ status: "error", error: error.message });
        }
    }

    function clear(message = "Choose a model from the library") {
        generation++;
        pendingLoader?.dispose();
        pendingLoader = null;
        activeModel?.dispose();
        activeModel = null;
        modelGroup.clear();
        jointsElement?.replaceChildren();
        showLoading(message);
        updateMetrics(null);
        resetView();
        requestRender();
    }

    function createJointControls(model) {
        if (!jointsElement) {
            return;
        }
        const joints = model.jointDefinitions();
        if (joints.length === 0) {
            const empty = document.createElement("p");
            empty.className = "empty-copy";
            empty.textContent = "This URDF has no movable joints.";
            jointsElement.replaceChildren(empty);
            return;
        }
        const fragment = document.createDocumentFragment();
        joints.forEach(joint => fragment.append(createJointControl(joint, model)));
        jointsElement.replaceChildren(fragment);
    }

    function createJointControl(joint, model) {
        const row = document.createElement("label");
        row.className = "joint-control";
        const title = document.createElement("span");
        title.className = "joint-name";
        title.textContent = joint.name;
        const value = document.createElement("output");
        const input = document.createElement("input");
        input.type = "range";

        const prismatic = joint.type === "prismatic";
        const minimum = finite(joint.lower) ? joint.lower : (prismatic ? -0.25 : -Math.PI);
        const maximum = finite(joint.upper) ? joint.upper : (prismatic ? 0.25 : Math.PI);
        const initial = Math.min(maximum, Math.max(minimum, 0));
        input.min = String(minimum);
        input.max = String(maximum);
        input.step = prismatic ? "0.001" : "0.01";
        input.value = String(initial);
        value.textContent = formatJointValue(initial, prismatic);
        input.addEventListener("input", () => {
            const next = Number(input.value);
            if (model.setJointValue(joint.name, next)) {
                value.textContent = formatJointValue(next, prismatic);
                requestRender();
            }
        });
        row.append(title, value, input);
        return row;
    }

    function resetPose() {
        if (!activeModel || !jointsElement) {
            return;
        }
        activeModel.resetPose();
        jointsElement.querySelectorAll('input[type="range"]').forEach(input => {
            const minimum = Number(input.min);
            const maximum = Number(input.max);
            const value = Math.min(maximum, Math.max(minimum, 0));
            input.value = String(value);
            input.dispatchEvent(new Event("input"));
        });
        requestRender();
    }

    function fitCamera(bounds) {
        if (!bounds || bounds.isEmpty()) {
            resetView();
            return;
        }
        const center = bounds.getCenter(new THREE.Vector3());
        const size = bounds.getSize(new THREE.Vector3());
        const largest = Math.max(size.x, size.y, size.z, 0.1);
        const distance = largest / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2))) * 1.45;
        const direction = new THREE.Vector3(1.25, 0.82, 1.05).normalize();
        controls.target.copy(center);
        camera.position.copy(center).addScaledVector(direction, distance);
        camera.near = Math.max(0.001, distance / 100);
        camera.far = Math.max(10, distance * 12);
        camera.updateProjectionMatrix();
        controls.update();
        requestRender();
    }

    function resetView() {
        if (activeModel) {
            fitCamera(activeModel.bounds());
            return;
        }
        camera.position.set(1.4, 0.9, 1.2);
        controls.target.set(0, 0.2, 0);
        camera.updateProjectionMatrix();
        controls.update();
        requestRender();
    }

    function rotate(angle) {
        const offset = camera.position.clone().sub(controls.target);
        offset.applyAxisAngle(new THREE.Vector3(0, 1, 0), angle);
        camera.position.copy(controls.target).add(offset);
        controls.update();
        requestRender();
    }

    function resize(width, height) {
        camera.aspect = width / height;
        camera.updateProjectionMatrix();
        renderer.setSize(width, height, false);
        requestRender();
    }

    function animationFrame() {
        if (disposed) {
            return;
        }
        const controlsChanged = controls.update();
        if (renderRequested || controlsChanged) {
            renderRequested = false;
            renderer.render(scene, camera);
        }
        window.requestAnimationFrame(animationFrame);
    }

    function requestRender() {
        renderRequested = true;
    }

    function showLoading(message, error = false) {
        if (!loadingElement) {
            return;
        }
        loadingElement.hidden = false;
        loadingElement.classList.toggle("is-error", error);
        if (loadingTextElement) {
            loadingTextElement.textContent = message;
        }
    }

    function hideLoading() {
        if (loadingElement) {
            loadingElement.hidden = true;
            loadingElement.classList.remove("is-error");
        }
    }

    function updateMetrics(status) {
        if (!metricsElement) {
            return;
        }
        if (!status) {
            metricsElement.textContent = "No model loaded";
            return;
        }
        const assets = `${status.loadedAssets || 0}/${status.totalAssets || 0} assets`;
        const triangles = `${Number(status.triangles || 0).toLocaleString()} triangles`;
        const load = finite(status.loadMs) ? `${(status.loadMs / 1000).toFixed(2)} s` : "loading";
        metricsElement.textContent = `${status.status || "loading"} · ${assets} · ${triangles} · ${load}`;
    }

    function dispose() {
        disposed = true;
        generation++;
        pendingLoader?.dispose();
        activeModel?.dispose();
        resizeObserver.disconnect();
        controls.dispose();
        renderer.dispose();
        container.replaceChildren();
    }
}

function progressText(status) {
    const total = status.totalAssets || 0;
    const loaded = status.loadedAssets || 0;
    return total > 0 ? `Loading meshes ${loaded}/${total}…` : "Discovering model assets…";
}

function formatJointValue(value, prismatic) {
    return prismatic ? `${value.toFixed(3)} m` : `${THREE.MathUtils.radToDeg(value).toFixed(1)}°`;
}

function finite(value) {
    return Number.isFinite(value);
}
