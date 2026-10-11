package com.oberasoftware.urdfviewer.model;

import java.time.Instant;

public record StoredUrdfModel(
        String id,
        String displayName,
        String robotName,
        Instant importedAt,
        String urdfFileName,
        int meshCount,
        long totalBytes,
        long triangleCount,
        String sourceSha256
) {
    public static StoredUrdfModel from(UrdfModelManifest manifest) {
        String path = manifest.urdfPath();
        int separator = path.lastIndexOf('/');
        String fileName = separator >= 0 ? path.substring(separator + 1) : path;
        return new StoredUrdfModel(
                manifest.id(),
                manifest.displayName(),
                manifest.robotName(),
                manifest.importedAt(),
                fileName,
                manifest.meshFileCount(),
                manifest.totalBytes(),
                manifest.triangleCount(),
                manifest.sourceSha256());
    }
}
