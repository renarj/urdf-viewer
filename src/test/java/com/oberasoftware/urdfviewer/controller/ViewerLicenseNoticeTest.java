package com.oberasoftware.urdfviewer.controller;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.service.ModelLibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ViewerLicenseNoticeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersAuthorPublishingYearAndLinksToBundledLegalNotices() throws Exception {
        var properties = new UrdfViewerProperties();
        properties.setStorageDir(temporaryDirectory.resolve("library"));
        var library = new ModelLibraryService(JsonMapper.builder().findAndAddModules().build(), properties);
        library.initialize();
        var templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        var mvc = MockMvcBuilders.standaloneSetup(new ViewerPageController(library, properties))
                .setViewResolvers(views)
                .build();

        String page = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).contains("<footer class=\"app-footer\">", "© 2026 Renze de Vries",
                "href=\"/urdf-viewer/legal/LICENSE\"", "PolyForm Noncommercial 1.0.0",
                "href=\"/urdf-viewer/legal/NOTICE\"");
    }

    @Test
    void bundlesCanonicalLicenseAndRequiredAttributionWithTheApplication() throws Exception {
        for (String name : List.of("LICENSE", "NOTICE")) {
            var resource = new ClassPathResource("META-INF/resources/urdf-viewer/legal/" + name);
            assertThat(resource.getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(Files.readString(Path.of(name), StandardCharsets.UTF_8));
        }
        var notice = new ClassPathResource("META-INF/resources/urdf-viewer/legal/NOTICE");
        assertThat(notice.getContentAsString(StandardCharsets.UTF_8))
                .contains("Required Notice: URDF Viewer — Copyright 2026 Renze de Vries");
    }
}
