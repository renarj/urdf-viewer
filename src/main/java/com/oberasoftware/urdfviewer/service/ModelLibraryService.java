package com.oberasoftware.urdfviewer.service;

import tools.jackson.databind.ObjectMapper;
import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.StoredUrdfModel;
import com.oberasoftware.urdfviewer.model.UrdfModelManifest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ModelLibraryService {
    private static final Logger LOG = LoggerFactory.getLogger(ModelLibraryService.class);
    private static final Pattern MODEL_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Path modelsRoot;
    private final Path stagingRoot;

    public ModelLibraryService(ObjectMapper objectMapper, UrdfViewerProperties properties) {
        this.objectMapper = objectMapper;
        this.storageRoot = properties.getStorageDir().toAbsolutePath().normalize();
        this.modelsRoot = storageRoot.resolve("models");
        this.stagingRoot = storageRoot.resolve(".staging");
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(modelsRoot);
            Files.createDirectories(stagingRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize URDF model storage at " + storageRoot, e);
        }
    }

    public List<StoredUrdfModel> listModels() {
        try (Stream<Path> directories = Files.list(modelsRoot)) {
            return directories
                    .filter(Files::isDirectory)
                    .map(this::readManifestIfValid)
                    .flatMap(java.util.Optional::stream)
                    .map(StoredUrdfModel::from)
                    .sorted(Comparator.comparing(StoredUrdfModel::importedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the URDF model library", e);
        }
    }

    public UrdfModelManifest getManifest(String modelId) {
        Path root = modelRoot(modelId);
        Path manifestPath = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "URDF model not found");
        }
        try {
            UrdfModelManifest manifest = objectMapper.readValue(manifestPath.toFile(), UrdfModelManifest.class);
            if (!modelId.equals(manifest.id())) {
                throw new IllegalStateException("Stored model manifest ID does not match its directory");
            }
            return manifest;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not read stored URDF model manifest", e);
        }
    }

    public Path createStagingDirectory() {
        try {
            return Files.createDirectory(stagingRoot.resolve(UUID.randomUUID().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not create a model import staging directory", e);
        }
    }

    public void writeManifest(Path stagingDirectory, UrdfModelManifest manifest) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(stagingDirectory.resolve("manifest.json").toFile(), manifest);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not write the model manifest", e);
        }
    }

    public void commit(Path stagingDirectory, String modelId) {
        Path target = modelRoot(modelId);
        try {
            try {
                Files.move(stagingDirectory, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(stagingDirectory, target);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not publish the imported URDF model", e);
        }
    }

    public void delete(String modelId) {
        Path root = modelRoot(modelId);
        if (!Files.exists(root)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "URDF model not found");
        }
        deleteTree(root);
    }

    public Path modelSourceRoot(String modelId) {
        Path source = modelRoot(modelId).resolve("source").normalize();
        if (!Files.isDirectory(source)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "URDF model assets not found");
        }
        return source;
    }

    public void discardStaging(Path stagingDirectory) {
        if (stagingDirectory != null && stagingDirectory.normalize().startsWith(stagingRoot)) {
            deleteTree(stagingDirectory);
        }
    }

    private java.util.Optional<UrdfModelManifest> readManifestIfValid(Path directory) {
        Path manifest = directory.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            LOG.warn("Ignoring URDF model directory without manifest: {}", directory.getFileName());
            return java.util.Optional.empty();
        }
        try {
            UrdfModelManifest value = objectMapper.readValue(manifest.toFile(), UrdfModelManifest.class);
            if (!directory.getFileName().toString().equals(value.id())) {
                LOG.warn("Ignoring URDF model whose manifest ID does not match directory: {}", directory.getFileName());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(value);
        } catch (RuntimeException e) {
            LOG.warn("Ignoring unreadable URDF model manifest {}: {}", manifest, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private Path modelRoot(String modelId) {
        if (modelId == null || !MODEL_ID.matcher(modelId).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "URDF model not found");
        }
        return modelsRoot.resolve(modelId);
    }

    private void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new DeleteFailure(e);
                }
            });
        } catch (DeleteFailure e) {
            throw new IllegalStateException("Could not delete " + root, e.getCause());
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect " + root + " for deletion", e);
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private DeleteFailure(IOException cause) {
            super(cause);
        }
    }
}
