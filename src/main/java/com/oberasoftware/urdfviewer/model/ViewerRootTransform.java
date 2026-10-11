package com.oberasoftware.urdfviewer.model;

import java.util.List;

public record ViewerRootTransform(
        List<Double> translationMeters,
        List<Double> rotationRpyDegrees,
        double scale
) {
    public static ViewerRootTransform urdfDefault() {
        return new ViewerRootTransform(
                List.of(0.0, 0.0, 0.0),
                List.of(-90.0, 0.0, 0.0),
                1.0);
    }
}
