package com.oberasoftware.urdfviewer.model;

import java.util.List;
import java.util.Map;

public record UrdfValidationReport(
        String robotName,
        String urdfPath,
        int fileCount,
        int meshReferenceCount,
        long totalBytes,
        long triangleCount,
        List<String> meshPaths,
        List<UrdfJointInfo> joints,
        Map<String, String> packageRoots
) {
}
