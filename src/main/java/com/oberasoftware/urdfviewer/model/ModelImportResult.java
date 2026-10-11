package com.oberasoftware.urdfviewer.model;

public record ModelImportResult(
        String sourceName,
        boolean success,
        StoredUrdfModel model,
        String error
) {
    public static ModelImportResult success(String sourceName, StoredUrdfModel model) {
        return new ModelImportResult(sourceName, true, model, null);
    }

    public static ModelImportResult failure(String sourceName, String error) {
        return new ModelImportResult(sourceName, false, null, error);
    }
}
