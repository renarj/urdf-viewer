import assert from "node:assert/strict";
import { test } from "node:test";
import * as THREE from "three";
import { placeModelOnFloor } from "../../main/resources/META-INF/resources/urdf-viewer/js/model-placement.js";

function near(actual, expected) {
    assert.ok(Math.abs(actual - expected) < 1e-10, `Expected ${actual} to equal ${expected}`);
}

function robotAt(height) {
    const root = new THREE.Group();
    root.rotation.x = -Math.PI / 2; // URDF Z-up to viewer Y-up.
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(2, 4, 6));
    mesh.position.z = height;
    root.add(mesh);
    return root;
}

for (const [description, height] of [["below", -4], ["above", 8], ["on", 3]]) {
    test(`places a model whose bottom starts ${description} the floor on Y=0`, () => {
        const root = robotAt(height);
        root.position.set(5, 0, -2);
        root.scale.setScalar(2);
        const rotation = root.quaternion.clone();
        const bounds = placeModelOnFloor(root);
        const actual = new THREE.Box3().setFromObject(root, true);

        near(actual.min.y, 0);
        near(actual.max.y, 12);
        for (const axis of ["x", "y", "z"]) {
            near(bounds.min[axis], actual.min[axis]);
            near(bounds.max[axis], actual.max[axis]);
        }
        assert.equal(root.position.x, 5);
        assert.equal(root.position.z, -2);
        assert.ok(root.quaternion.equals(rotation));
        assert.equal(root.scale.x, 2);
    });
}

test("uses the lowest transformed vertex of irregular, rotated visuals", () => {
    const root = new THREE.Group();
    root.position.y = 5;
    const link = new THREE.Group();
    link.rotation.z = Math.PI / 4;
    link.position.y = -2;
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.Float32BufferAttribute([
        -1, 1, 0, 1, -1, 0, 1, 1, 0
    ], 3));
    link.add(new THREE.Mesh(geometry));
    root.add(link);
    // A transformed local AABB would falsely put the bottom sqrt(2) lower.
    const bounds = placeModelOnFloor(root);

    near(root.position.y, 2);
    near(bounds.min.y, 0);
    near(new THREE.Box3().setFromObject(root, true).min.y, 0);
});

test("placement is repeatable and independent for each loaded model", () => {
    const first = robotAt(-5);
    placeModelOnFloor(first);
    const position = first.position.clone();
    placeModelOnFloor(first);
    assert.ok(first.position.distanceTo(position) < 1e-10);

    const second = robotAt(10);
    placeModelOnFloor(second);
    near(new THREE.Box3().setFromObject(second, true).min.y, 0);
    assert.ok(first.position.equals(position));
});

test("empty models keep their transform and return empty bounds", () => {
    const root = new THREE.Group();
    root.position.set(1, 2, 3);
    const bounds = placeModelOnFloor(root);
    assert.ok(bounds.isEmpty());
    assert.deepEqual(root.position.toArray(), [1, 2, 3]);
});
