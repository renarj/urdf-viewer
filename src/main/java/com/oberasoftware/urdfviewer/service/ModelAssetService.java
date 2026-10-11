package com.oberasoftware.urdfviewer.service;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class ModelAssetService {
    private final ModelLibraryService library;

    public ModelAssetService(ModelLibraryService library) {
        this.library = library;
    }

    public ModelAsset resolveUrdf(String modelId) {
        return resolve(modelId, library.getManifest(modelId).urdfPath());
    }

    public ModelAsset resolve(String modelId, String assetPath) {
        if (assetPath == null || assetPath.isBlank() || assetPath.indexOf('\\') >= 0
                || assetPath.indexOf('\0') >= 0) {
            throw notFound();
        }
        String normalizedInput = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        Path sourceRoot = library.modelSourceRoot(modelId).toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = sourceRoot.resolve(normalizedInput).normalize().toAbsolutePath();
        } catch (RuntimeException e) {
            throw notFound();
        }
        if (!resolved.startsWith(sourceRoot) || !Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved)) {
            throw notFound();
        }
        return new ModelAsset(new FileSystemResource(resolved), contentType(resolved));
    }

    private MediaType contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".urdf") || name.endsWith(".xml")) {
            return MediaType.APPLICATION_XML;
        }
        if (name.endsWith(".stl")) {
            return MediaType.parseMediaType("model/stl");
        }
        if (name.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".license")) {
            return MediaType.TEXT_PLAIN;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Model asset not found");
    }

    public record ModelAsset(Resource resource, MediaType contentType) {
    }
}
