package com.oberasoftware.urdfviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "urdf.viewer")
public class UrdfViewerProperties {
    private Path storageDir = Path.of("data", "urdf-viewer");
    private long maxUploadBytes = 128L * 1024 * 1024;
    private long maxExtractedBytes = 256L * 1024 * 1024;
    private long maxFileBytes = 64L * 1024 * 1024;
    private int maxFiles = 1_000;
    private int maxMeshes = 300;
    private long maxStlBytes = 256L * 1024 * 1024;
    private long maxTriangles = 5_000_000;

    public Path getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(Path storageDir) {
        this.storageDir = storageDir;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public long getMaxExtractedBytes() {
        return maxExtractedBytes;
    }

    public void setMaxExtractedBytes(long maxExtractedBytes) {
        this.maxExtractedBytes = maxExtractedBytes;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxMeshes() {
        return maxMeshes;
    }

    public void setMaxMeshes(int maxMeshes) {
        this.maxMeshes = maxMeshes;
    }

    public long getMaxStlBytes() {
        return maxStlBytes;
    }

    public void setMaxStlBytes(long maxStlBytes) {
        this.maxStlBytes = maxStlBytes;
    }

    public long getMaxTriangles() {
        return maxTriangles;
    }

    public void setMaxTriangles(long maxTriangles) {
        this.maxTriangles = maxTriangles;
    }
}
