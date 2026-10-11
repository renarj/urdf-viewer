import * as THREE from "three";

// Called once after loading, before the root is attached to the scene. Keep this
// offset during articulation so moving a joint does not move the entire robot.
export function placeModelOnFloor(root) {
    // Measure actual transformed vertices: rotating a local bounding box can
    // overestimate its extent and leave a gap underneath an irregular mesh.
    const bounds = new THREE.Box3().setFromObject(root, true);
    if (bounds.isEmpty() || !Number.isFinite(bounds.min.y)) {
        return bounds;
    }
    const offset = -bounds.min.y;
    root.position.y += offset;
    root.updateMatrixWorld(true);
    bounds.translate(new THREE.Vector3(0, offset, 0));
    return bounds;
}
