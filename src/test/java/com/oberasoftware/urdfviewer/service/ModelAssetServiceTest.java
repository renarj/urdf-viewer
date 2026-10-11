package com.oberasoftware.urdfviewer.service;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.UrdfModelManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelAssetServiceTest {
    @TempDir
    Path temporaryDirectory;

    private ModelLibraryService library;
    private ModelAssetService assets;
    private String modelId;

    @BeforeEach
    void setUp() throws Exception {
        UrdfViewerProperties properties = new UrdfViewerProperties();
        properties.setStorageDir(temporaryDirectory.resolve("library"));
        library = new ModelLibraryService(JsonMapper.builder().findAndAddModules().build(), properties);
        library.initialize();
        modelId = UUID.randomUUID().toString();
        Path staging = library.createStagingDirectory();
        Files.createDirectories(staging.resolve("source/meshes"));
        Files.writeString(staging.resolve("source/robot.urdf"), "<robot name=\"test\"><link name=\"base\"/></robot>");
        Files.write(staging.resolve("source/meshes/base.stl"), new byte[]{1, 2, 3});
        library.writeManifest(staging, new UrdfModelManifest(
                1, modelId, "Test", "test", Instant.now(), "hash", "robot.urdf",
                2, 1, 1, 3, 0, List.of("meshes/base.stl"), List.of(), Map.of()));
        library.commit(staging, modelId);
        assets = new ModelAssetService(library);
    }

    @Test
    void servesAssetBelowModelRoot() throws Exception {
        ModelAssetService.ModelAsset asset = assets.resolve(modelId, "/meshes/base.stl");

        assertThat(asset.contentType().toString()).isEqualTo("model/stl");
        assertThat(asset.resource().getContentAsByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> assets.resolve(modelId, "../../manifest.json"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
