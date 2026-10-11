package com.oberasoftware.urdfviewer.model;

import java.util.Map;

public record ViewerDefinition(
        String type,
        String urdfUrl,
        String assetRootUrl,
        Map<String, String> packages,
        ViewerRootTransform rootTransform,
        ViewerLimits limits
) {
}
