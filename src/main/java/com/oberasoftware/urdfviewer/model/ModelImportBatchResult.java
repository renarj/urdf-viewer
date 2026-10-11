package com.oberasoftware.urdfviewer.model;

import java.util.List;

public record ModelImportBatchResult(List<ModelImportResult> results) {
    public ModelImportBatchResult {
        results = List.copyOf(results);
    }
}
