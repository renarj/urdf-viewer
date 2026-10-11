package com.oberasoftware.urdfviewer.model;

public record UrdfModelDetails(
        UrdfModelManifest manifest,
        ViewerDefinition viewer
) {
}
