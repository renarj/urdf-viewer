package com.oberasoftware.urdfviewer.controller;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.ModelImportBatchResult;
import com.oberasoftware.urdfviewer.model.StoredUrdfModel;
import com.oberasoftware.urdfviewer.model.UrdfModelDetails;
import com.oberasoftware.urdfviewer.model.UrdfModelManifest;
import com.oberasoftware.urdfviewer.model.ViewerDefinition;
import com.oberasoftware.urdfviewer.model.ViewerLimits;
import com.oberasoftware.urdfviewer.model.ViewerRootTransform;
import com.oberasoftware.urdfviewer.service.InvalidUrdfModelException;
import com.oberasoftware.urdfviewer.service.ModelAssetService;
import com.oberasoftware.urdfviewer.service.ModelImportService;
import com.oberasoftware.urdfviewer.service.ModelLibraryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelLibraryRestController {
    private final ModelLibraryService library;
    private final ModelImportService importService;
    private final ModelAssetService assetService;
    private final UrdfViewerProperties properties;

    public ModelLibraryRestController(
            ModelLibraryService library,
            ModelImportService importService,
            ModelAssetService assetService,
            UrdfViewerProperties properties
    ) {
        this.library = library;
        this.importService = importService;
        this.assetService = assetService;
        this.properties = properties;
    }

    @GetMapping
    public List<StoredUrdfModel> models() {
        return library.listModels();
    }

    @PostMapping(path = "/import", consumes = "multipart/form-data")
    public ModelImportBatchResult importModels(
            @RequestParam(name = "bundles", required = false) List<MultipartFile> bundles,
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(name = "displayName", required = false) String displayName
    ) {
        boolean hasBundles = bundles != null && !bundles.isEmpty();
        boolean hasFiles = files != null && !files.isEmpty();
        if (hasBundles == hasFiles) {
            throw new InvalidUrdfModelException("Upload either ZIP bundles or one model directory");
        }
        return hasBundles
                ? importService.importBundles(bundles, displayName)
                : importService.importDirectory(files, displayName);
    }

    @GetMapping("/{modelId}")
    public UrdfModelDetails model(@PathVariable String modelId) {
        UrdfModelManifest manifest = library.getManifest(modelId);
        String base = "/api/models/" + modelId;
        String assetRoot = base + "/assets/";
        Map<String, String> packages = new LinkedHashMap<>();
        manifest.packageRoots().forEach((name, path) -> packages.put(name,
                assetRoot + encodedPath(path) + (path.isBlank() ? "" : "/")));
        ViewerDefinition viewer = new ViewerDefinition(
                "urdf",
                assetRoot + encodedPath(manifest.urdfPath()),
                assetRoot,
                Map.copyOf(packages),
                ViewerRootTransform.urdfDefault(),
                new ViewerLimits(
                        properties.getMaxMeshes(),
                        properties.getMaxStlBytes(),
                        properties.getMaxTriangles()));
        return new UrdfModelDetails(manifest, viewer);
    }

    @GetMapping("/{modelId}/urdf")
    public ResponseEntity<org.springframework.core.io.Resource> urdf(@PathVariable String modelId) {
        return assetResponse(assetService.resolveUrdf(modelId));
    }

    @GetMapping("/{modelId}/assets/{*assetPath}")
    public ResponseEntity<org.springframework.core.io.Resource> asset(
            @PathVariable String modelId,
            @PathVariable String assetPath
    ) {
        return assetResponse(assetService.resolve(modelId, assetPath));
    }

    @DeleteMapping("/{modelId}")
    public ResponseEntity<Void> delete(@PathVariable String modelId) {
        library.delete(modelId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<org.springframework.core.io.Resource> assetResponse(ModelAssetService.ModelAsset asset) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentType(asset.contentType())
                .body(asset.resource());
    }

    private String encodedPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(path.split("/"))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("/"));
    }
}
