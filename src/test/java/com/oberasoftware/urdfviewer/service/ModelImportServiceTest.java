package com.oberasoftware.urdfviewer.service;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.ModelImportBatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelImportServiceTest {
    @TempDir
    Path temporaryDirectory;

    private UrdfViewerProperties properties;
    private ObjectMapper objectMapper;
    private ModelLibraryService library;
    private ModelImportService importService;

    @BeforeEach
    void setUp() {
        properties = new UrdfViewerProperties();
        properties.setStorageDir(temporaryDirectory.resolve("library"));
        properties.setMaxUploadBytes(1024 * 1024);
        properties.setMaxExtractedBytes(1024 * 1024);
        properties.setMaxFileBytes(512 * 1024);
        properties.setMaxFiles(30);
        properties.setMaxMeshes(10);
        properties.setMaxTriangles(100);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        library = new ModelLibraryService(objectMapper, properties);
        library.initialize();
        importService = new ModelImportService(library, new UrdfValidationService(properties), properties);
    }

    @Test
    void importsValidZipAndPersistsManifest() throws IOException {
        MockMultipartFile bundle = zipBundle("walker.zip", Map.of(
                "walker/robot.urdf", urdf("meshes/base.stl").getBytes(),
                "walker/meshes/base.stl", binaryStl()));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), "Test walker");

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isTrue();
            assertThat(imported.model().displayName()).isEqualTo("Test walker");
            assertThat(imported.model().meshCount()).isEqualTo(1);
            assertThat(imported.model().triangleCount()).isEqualTo(1);
            assertThat(imported.model().sourceSha256()).hasSize(64);
        });
        assertThat(library.listModels()).hasSize(1);

        ModelLibraryService restartedLibrary = new ModelLibraryService(objectMapper, properties);
        restartedLibrary.initialize();
        assertThat(restartedLibrary.listModels()).singleElement()
                .extracting("displayName").isEqualTo("Test walker");
    }

    @Test
    void importsDirectoryWithBrowserRelativePaths() {
        MockMultipartFile urdf = new MockMultipartFile(
                "files", "walker/robot.urdf", "application/xml", urdf("meshes/base.stl").getBytes());
        MockMultipartFile mesh = new MockMultipartFile(
                "files", "walker/meshes/base.stl", "model/stl", binaryStl());

        ModelImportBatchResult result = importService.importDirectory(List.of(urdf, mesh), "Folder walker");

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isTrue();
            assertThat(imported.model().displayName()).isEqualTo("Folder walker");
        });
        assertThat(library.listModels()).hasSize(1);
    }

    @Test
    void resolvesPackageMeshInsideUploadedBundle() throws IOException {
        MockMultipartFile bundle = zipBundle("package-model.zip", Map.of(
                "my_robot/robot.urdf", urdf("package://my_robot/meshes/base.stl").getBytes(),
                "my_robot/meshes/base.stl", binaryStl()));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), null);

        String modelId = result.results().getFirst().model().id();
        assertThat(result.results().getFirst().success()).isTrue();
        assertThat(library.getManifest(modelId).packageRoots())
                .containsEntry("my_robot", "my_robot");
    }

    @Test
    void importsPrimaryUrdfWhileSkippingUnrelatedBundleArtifacts() throws IOException {
        MockMultipartFile bundle = zipBundle("humanoid_urdf.zip", Map.of(
                "humanoid_urdf/humanoid.urdf", urdf("meshes/base.stl").getBytes(),
                "humanoid_urdf/humanoid_cad_reference.urdf", urdf("meshes/reference.stl").getBytes(),
                "humanoid_urdf/meshes/base.stl", binaryStl(),
                "humanoid_urdf/meshes/reference.stl", binaryStl(),
                "humanoid_urdf/arm_motion_preview.png", new byte[]{1, 2, 3},
                "humanoid_urdf/tools/build_urdf.py", "print('build')".getBytes(),
                "humanoid_urdf/source/mesh_snapshot.zip", new byte[]{4, 5, 6}));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), null);

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isTrue();
            assertThat(library.getManifest(imported.model().id()).urdfPath())
                    .isEqualTo("humanoid_urdf/humanoid.urdf");
            Path source = library.modelSourceRoot(imported.model().id());
            assertThat(source.resolve("humanoid_urdf/arm_motion_preview.png")).doesNotExist();
            assertThat(source.resolve("humanoid_urdf/tools/build_urdf.py")).doesNotExist();
            assertThat(source.resolve("humanoid_urdf/source/mesh_snapshot.zip")).doesNotExist();
        });
    }

    @Test
    void rejectsMultipleAmbiguousPrimaryUrdfs() throws IOException {
        MockMultipartFile bundle = zipBundle("ambiguous.zip", Map.of(
                "first.urdf", urdf("meshes/base.stl").getBytes(),
                "second.urdf", urdf("meshes/base.stl").getBytes(),
                "meshes/base.stl", binaryStl()));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), null);

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isFalse();
            assertThat(imported.error()).contains("must identify one primary model");
        });
        assertThat(library.listModels()).isEmpty();
    }

    @Test
    void batchImportReportsEachArchiveIndependently() throws IOException {
        MockMultipartFile valid = zipBundle("valid.zip", Map.of(
                "robot.urdf", urdf("meshes/base.stl").getBytes(),
                "meshes/base.stl", binaryStl()));
        MockMultipartFile invalid = zipBundle("invalid.zip", Map.of(
                "robot.urdf", urdf("meshes/missing.stl").getBytes()));

        ModelImportBatchResult result = importService.importBundles(List.of(valid, invalid), null);

        assertThat(result.results()).hasSize(2);
        assertThat(result.results()).extracting("success").containsExactly(true, false);
        assertThat(library.listModels()).hasSize(1);
    }

    @Test
    void rejectsMissingMeshWithoutPublishingPartialModel() throws IOException {
        MockMultipartFile bundle = zipBundle("broken.zip", Map.of(
                "robot.urdf", urdf("meshes/missing.stl").getBytes()));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), null);

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isFalse();
            assertThat(imported.error()).contains("does not exist");
        });
        assertThat(library.listModels()).isEmpty();
        try (var staging = Files.list(properties.getStorageDir().resolve(".staging"))) {
            assertThat(staging).isEmpty();
        }
    }

    @Test
    void rejectsZipSlipPath() throws IOException {
        MockMultipartFile bundle = zipBundle("unsafe.zip", Map.of(
                "../robot.urdf", urdf("meshes/base.stl").getBytes(),
                "meshes/base.stl", binaryStl()));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), null);

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isFalse();
            assertThat(imported.error()).contains("Unsafe model path");
        });
        assertThat(Files.exists(temporaryDirectory.resolve("robot.urdf"))).isFalse();
        assertThat(library.listModels()).isEmpty();
    }

    @Test
    void rejectsUrdfExternalEntityDeclaration() throws IOException {
        String unsafe = """
                <?xml version="1.0"?>
                <!DOCTYPE robot [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <robot name="unsafe"><link name="&xxe;"/></robot>
                """;
        MockMultipartFile bundle = zipBundle("unsafe-xml.zip", Map.of("robot.urdf", unsafe.getBytes()));

        ModelImportBatchResult result = importService.importBundles(List.of(bundle), null);

        assertThat(result.results()).singleElement().satisfies(imported -> {
            assertThat(imported.success()).isFalse();
            assertThat(imported.error()).contains("external declarations");
        });
        assertThat(library.listModels()).isEmpty();
    }

    private MockMultipartFile zipBundle(String name, Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("bundles", name, "application/zip", bytes.toByteArray());
    }

    private String urdf(String meshPath) {
        return """
                <?xml version="1.0"?>
                <robot name="test_walker">
                  <link name="base">
                    <visual><geometry><mesh filename="%s"/></geometry></visual>
                  </link>
                  <link name="leg"/>
                  <joint name="hip" type="revolute">
                    <parent link="base"/><child link="leg"/><axis xyz="0 1 0"/>
                    <limit lower="-1.0" upper="1.0" effort="1" velocity="1"/>
                  </joint>
                </robot>
                """.formatted(meshPath);
    }

    private byte[] binaryStl() {
        byte[] stl = new byte[84 + 50];
        ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN).putInt(80, 1);
        return stl;
    }
}
