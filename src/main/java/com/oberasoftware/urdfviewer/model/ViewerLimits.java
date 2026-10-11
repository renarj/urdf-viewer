package com.oberasoftware.urdfviewer.model;

public record ViewerLimits(
        int maxMeshes,
        long maxStlBytes,
        long maxTriangles
) {
}
