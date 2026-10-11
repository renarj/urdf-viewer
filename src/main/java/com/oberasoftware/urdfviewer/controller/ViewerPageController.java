package com.oberasoftware.urdfviewer.controller;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.service.ModelLibraryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewerPageController {
    private final ModelLibraryService library;
    private final UrdfViewerProperties properties;

    public ViewerPageController(ModelLibraryService library, UrdfViewerProperties properties) {
        this.library = library;
        this.properties = properties;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("models", library.listModels());
        model.addAttribute("maxUploadMegabytes", properties.getMaxUploadBytes() / (1024 * 1024));
        model.addAttribute("maxMeshes", properties.getMaxMeshes());
        model.addAttribute("maxTriangles", properties.getMaxTriangles());
        return "index";
    }
}
