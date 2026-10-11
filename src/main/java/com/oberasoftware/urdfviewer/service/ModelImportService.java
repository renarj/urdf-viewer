package com.oberasoftware.urdfviewer.service;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.ModelImportBatchResult;
import com.oberasoftware.urdfviewer.model.ModelImportResult;
import com.oberasoftware.urdfviewer.model.StoredUrdfModel;
import com.oberasoftware.urdfviewer.model.UrdfModelManifest;
import com.oberasoftware.urdfviewer.model.UrdfValidationReport;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ModelImportService {
    private static final Logger LOG = LoggerFactory.getLogger(ModelImportService.class);
    private static final int BUFFER_SIZE = 16 * 1024;

    private final ModelLibraryService library;
    private final UrdfValidationService validationService;
    private final UrdfViewerProperties properties;

    public ModelImportService(
            ModelLibraryService library,
            UrdfValidationService validationService,
            UrdfViewerProperties properties
    ) {
        this.library = library;
        this.validationService = validationService;
        this.properties = properties;
    }

    public ModelImportBatchResult importBundles(List<MultipartFile> bundles, String requestedDisplayName) {
        if (bundles == null || bundles.isEmpty()) {
            throw new InvalidUrdfModelException("Select at least one ZIP model bundle");
        }
        List<ModelImportResult> results = new ArrayList<>();
        for (MultipartFile bundle : bundles) {
            String sourceName = safeSourceName(bundle.getOriginalFilename(), "model.zip");
            try {
                if (bundle.isEmpty()) {
                    throw new InvalidUrdfModelException("The ZIP bundle is empty");
                }
                if (!sourceName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    throw new InvalidUrdfModelException("Model bundles must use the .zip extension");
                }
                if (bundle.getSize() > properties.getMaxUploadBytes()) {
                    throw new InvalidUrdfModelException("The ZIP bundle exceeds the configured upload limit");
                }
                String displayName = bundles.size() == 1 && requestedDisplayName != null
                        ? requestedDisplayName
                        : stripExtension(sourceName);
                results.add(ModelImportResult.success(sourceName, importZip(bundle, displayName)));
            } catch (RuntimeException e) {
                logImportFailure(sourceName, e);
                results.add(ModelImportResult.failure(sourceName, publicMessage(e)));
            }
        }
        return new ModelImportBatchResult(results);
    }

    public ModelImportBatchResult importDirectory(List<MultipartFile> files, String requestedDisplayName) {
        if (files == null || files.isEmpty()) {
            throw new InvalidUrdfModelException("Select a model directory");
        }
        String sourceName = requestedDisplayName == null || requestedDisplayName.isBlank()
                ? "Uploaded directory"
                : requestedDisplayName.trim();
        try {
            return new ModelImportBatchResult(List.of(ModelImportResult.success(
                    sourceName,
                    importFiles(files, requestedDisplayName))));
        } catch (RuntimeException e) {
            logImportFailure(sourceName, e);
            return new ModelImportBatchResult(List.of(ModelImportResult.failure(sourceName, publicMessage(e))));
        }
    }

    private StoredUrdfModel importZip(MultipartFile bundle, String requestedDisplayName) {
        Path staging = library.createStagingDirectory();
        Path source = staging.resolve("source");
        try {
            Files.createDirectory(source);
            extractZip(bundle, source);
            return validateAndCommit(staging, source, requestedDisplayName);
        } catch (IOException e) {
            library.discardStaging(staging);
            throw new InvalidUrdfModelException("Could not read the ZIP bundle", e);
        } catch (RuntimeException e) {
            library.discardStaging(staging);
            throw e;
        }
    }

    private StoredUrdfModel importFiles(List<MultipartFile> files, String requestedDisplayName) {
        long uploadBytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (uploadBytes > properties.getMaxUploadBytes()) {
            throw new InvalidUrdfModelException("The uploaded directory exceeds the configured upload limit");
        }
        if (files.size() > properties.getMaxFiles()) {
            throw new InvalidUrdfModelException("The uploaded directory exceeds the configured file limit");
        }

        Path staging = library.createStagingDirectory();
        Path source = staging.resolve("source");
        try {
            Files.createDirectory(source);
            Set<String> paths = new LinkedHashSet<>();
            long[] extracted = {0};
            for (MultipartFile file : files) {
                if (file.isEmpty() && isIgnoredMetadata(file.getOriginalFilename())) {
                    continue;
                }
                String name = safeEntryName(file.getOriginalFilename());
                if (isIgnoredMetadata(name)) {
                    continue;
                }
                if (!isViewerModelFile(name)) {
                    LOG.debug("Skipping auxiliary file in uploaded model directory: {}", name);
                    continue;
                }
                if (!paths.add(pathKey(name))) {
                    throw new InvalidUrdfModelException("Duplicate model path: " + name);
                }
                if (file.getSize() > properties.getMaxFileBytes()) {
                    throw new InvalidUrdfModelException("File exceeds the per-file limit: " + name);
                }
                extracted[0] = Math.addExact(extracted[0], file.getSize());
                requireExtractedLimit(extracted[0]);
                Path target = safeTarget(source, name);
                Files.createDirectories(target.getParent());
                try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
                    copyLimited(input, output, name, extracted, file.getSize());
                }
            }
            return validateAndCommit(staging, source, requestedDisplayName);
        } catch (IOException e) {
            library.discardStaging(staging);
            throw new InvalidUrdfModelException("Could not store the uploaded model directory", e);
        } catch (ArithmeticException e) {
            library.discardStaging(staging);
            throw new InvalidUrdfModelException("The uploaded directory size is invalid", e);
        } catch (RuntimeException e) {
            library.discardStaging(staging);
            throw e;
        }
    }

    private void extractZip(MultipartFile bundle, Path source) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        int fileCount = 0;
        long[] extracted = {0};
        try (InputStream raw = bundle.getInputStream(); ZipArchiveInputStream zip = new ZipArchiveInputStream(raw)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (isIgnoredMetadata(rawName)) {
                    continue;
                }
                if (!zip.canReadEntryData(entry)) {
                    throw new InvalidUrdfModelException("ZIP entry uses unsupported compression or encryption: " + rawName);
                }
                if (entry.isUnixSymlink()) {
                    throw new InvalidUrdfModelException("Symbolic links are not allowed in model bundles: " + rawName);
                }
                String name = safeEntryName(rawName);
                Path target = safeTarget(source, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                fileCount++;
                if (fileCount > properties.getMaxFiles()) {
                    throw new InvalidUrdfModelException("The ZIP bundle exceeds the configured file limit");
                }
                if (entry.getSize() > properties.getMaxFileBytes()) {
                    throw new InvalidUrdfModelException("File exceeds the per-file limit: " + name);
                }
                if (!isViewerModelFile(name)) {
                    LOG.debug("Skipping auxiliary file in uploaded model bundle: {}", name);
                    continue;
                }
                if (!paths.add(pathKey(name))) {
                    throw new InvalidUrdfModelException("Duplicate model path: " + name);
                }
                Files.createDirectories(target.getParent());
                long before = extracted[0];
                try (OutputStream output = Files.newOutputStream(target)) {
                    copyLimited(zip, output, name, extracted, -1);
                }
                if (entry.getSize() >= 0 && extracted[0] - before != entry.getSize()) {
                    throw new InvalidUrdfModelException("ZIP entry size does not match its metadata: " + name);
                }
            }
        }
    }

    private void copyLimited(
            InputStream input,
            OutputStream output,
            String name,
            long[] extracted,
            long alreadyCounted
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long fileBytes = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            fileBytes += count;
            if (fileBytes > properties.getMaxFileBytes()) {
                throw new InvalidUrdfModelException("File exceeds the per-file limit: " + name);
            }
            if (alreadyCounted < 0) {
                extracted[0] = Math.addExact(extracted[0], count);
                requireExtractedLimit(extracted[0]);
            }
            output.write(buffer, 0, count);
        }
        if (alreadyCounted >= 0 && fileBytes != alreadyCounted) {
            throw new InvalidUrdfModelException("Uploaded file size changed while importing: " + name);
        }
    }

    private StoredUrdfModel validateAndCommit(Path staging, Path source, String requestedDisplayName) {
        UrdfValidationReport report = validationService.validate(source);
        String id = UUID.randomUUID().toString();
        String displayName = normalizedDisplayName(requestedDisplayName, report.robotName());
        UrdfModelManifest manifest = new UrdfModelManifest(
                1,
                id,
                displayName,
                report.robotName(),
                Instant.now(),
                contentHash(source),
                report.urdfPath(),
                report.fileCount(),
                report.meshReferenceCount(),
                report.meshPaths().size(),
                report.totalBytes(),
                report.triangleCount(),
                report.meshPaths(),
                report.joints(),
                report.packageRoots());
        library.writeManifest(staging, manifest);
        library.commit(staging, id);
        return StoredUrdfModel.from(manifest);
    }

    private String contentHash(Path source) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> source.relativize(p).toString()))
                    .toList()) {
                String relative = source.relativize(path).toString().replace('\\', '/');
                digest.update(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                digest.update((byte) 0xff);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Could not hash the imported model", e);
        }
    }

    private Path safeTarget(Path source, String name) {
        Path target = source.resolve(name).normalize();
        if (!target.startsWith(source)) {
            throw new InvalidUrdfModelException("Model path escapes the upload root: " + name);
        }
        return target;
    }

    private String safeEntryName(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidUrdfModelException("A model file has no name");
        }
        String name = value.replaceFirst("^\\./", "");
        if (name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || name.indexOf('%') >= 0 || name.startsWith("/")
                || name.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.asList(name.split("/", -1)).contains("..")
                || name.matches("^[A-Za-z]:.*")) {
            throw new InvalidUrdfModelException("Unsafe model path: " + value);
        }
        Path normalized;
        try {
            normalized = Path.of(name).normalize();
        } catch (RuntimeException e) {
            throw new InvalidUrdfModelException("Invalid model path: " + value, e);
        }
        String result = normalized.toString().replace('\\', '/');
        if (result.isBlank() || result.equals(".") || result.equals("..") || result.startsWith("../")) {
            throw new InvalidUrdfModelException("Unsafe model path: " + value);
        }
        return result;
    }

    private String pathKey(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private boolean isViewerModelFile(String name) {
        String fileName = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return fileName.endsWith(".urdf")
                || fileName.endsWith(".stl")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".json")
                || fileName.endsWith(".md")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".license")
                || fileName.equals("license")
                || fileName.equals("copying");
    }

    private boolean isIgnoredMetadata(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("__MACOSX/") || normalized.endsWith("/.DS_Store")
                || normalized.equals(".DS_Store");
    }

    private void requireExtractedLimit(long extracted) {
        if (extracted > properties.getMaxExtractedBytes()) {
            throw new InvalidUrdfModelException("The extracted model exceeds the configured size limit");
        }
    }

    private String normalizedDisplayName(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested.trim();
        value = value.replaceAll("[\\p{Cntrl}]", "").strip();
        if (value.isBlank()) {
            value = "URDF model";
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private String safeSourceName(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String publicMessage(RuntimeException error) {
        if (error instanceof InvalidUrdfModelException && error.getMessage() != null) {
            return error.getMessage();
        }
        return "The model could not be imported; see the server log for details";
    }

    private void logImportFailure(String sourceName, RuntimeException error) {
        if (error instanceof InvalidUrdfModelException) {
            LOG.warn("URDF import rejected for {}: {}", sourceName, error.getMessage());
        } else {
            LOG.error("URDF import failed unexpectedly for {}", sourceName, error);
        }
    }
}
