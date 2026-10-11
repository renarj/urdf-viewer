package com.oberasoftware.urdfviewer.model;

public record UrdfJointInfo(
        String name,
        String type,
        Double lowerLimitRadians,
        Double upperLimitRadians
) {
}
