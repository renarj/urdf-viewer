package com.oberasoftware.urdfviewer.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record UrdfModelManifest(
        int schemaVersion,
        String id,
        String displayName,
        String robotName,
        Instant importedAt,
        String sourceSha256,
        String urdfPath,
        int fileCount,
        int meshReferenceCount,
        int meshFileCount,
        long totalBytes,
        long triangleCount,
        List<String> meshPaths,
        List<UrdfJointInfo> joints,
        Map<String, String> packageRoots
) {
    public UrdfModelManifest {
        meshPaths = List.copyOf(meshPaths);
        joints = List.copyOf(joints);
        packageRoots = Map.copyOf(packageRoots);
    }
}
