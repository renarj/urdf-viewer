package com.oberasoftware.urdfviewer.service;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.UrdfValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UrdfValidationCompatibilityTest {
    @Test
    void defaultSafetyEnvelopeSupportsDetailedAssemblyModels() {
        UrdfViewerProperties properties = new UrdfViewerProperties();

        assertThat(properties.getMaxUploadBytes()).isEqualTo(128L * 1024 * 1024);
        assertThat(properties.getMaxExtractedBytes()).isEqualTo(256L * 1024 * 1024);
        assertThat(properties.getMaxFileBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(properties.getMaxStlBytes()).isEqualTo(256L * 1024 * 1024);
        assertThat(properties.getMaxTriangles()).isEqualTo(5_000_000);
    }

    @Test
    void validatesStandaloneUrdfAndStlRegressionFixture(@TempDir Path model) throws IOException {
        Files.createDirectories(model.resolve("meshes"));
        Files.writeString(model.resolve("robot.urdf"), """
                <?xml version="1.0"?>
                <robot name="portable_fixture">
                  <link name="base">
                    <visual><geometry><mesh filename="meshes/link.stl"/></geometry></visual>
                  </link>
                  <link name="arm"/>
                  <joint name="shoulder" type="revolute">
                    <parent link="base"/>
                    <child link="arm"/>
                    <limit lower="-1.5" upper="1.5" effort="1" velocity="1"/>
                  </joint>
                </robot>
                """);
        Files.writeString(model.resolve("meshes/link.stl"), """
                solid link
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 1 0 0
                      vertex 0 1 0
                    endloop
                  endfacet
                endsolid link
                """);
        UrdfViewerProperties properties = new UrdfViewerProperties();

        UrdfValidationReport report = new UrdfValidationService(properties).validate(model);

        assertThat(report.robotName()).isEqualTo("portable_fixture");
        assertThat(report.meshReferenceCount()).isOne();
        assertThat(report.meshPaths()).containsExactly("meshes/link.stl");
        assertThat(report.joints()).singleElement().satisfies(joint -> {
            assertThat(joint.name()).isEqualTo("shoulder");
            assertThat(joint.type()).isEqualTo("revolute");
        });
        assertThat(report.triangleCount()).isOne();
    }
}
