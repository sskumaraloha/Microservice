package com.enterprise.ems.employee.storage;

import com.enterprise.ems.employee.exception.InvalidFileException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two deliberate anti-path-traversal decisions:
 * <ol>
 *   <li>The client-supplied filename is stored as metadata only — it is
 *       NEVER used to build a filesystem path. A crafted name like
 *       {@code ../../etc/passwd} can't escape the storage root because the
 *       actual on-disk name is always a fresh random UUID this class
 *       generates itself.</li>
 *   <li>The stored file's extension comes from a fixed allow-list keyed by
 *       content type, not from parsing the original filename — so a file
 *       named {@code resume.pdf.sh} can't smuggle an executable extension
 *       onto disk.</li>
 * </ol>
 */
@Component
public class LocalFileSystemStorageAdapter implements StoragePort {

    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "application/pdf", "pdf",
            "image/png", "png",
            "image/jpeg", "jpg",
            "application/msword", "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

    private final StorageProperties properties;
    private final Set<String> allowedContentTypes;
    private final Path rootDirectory;

    public LocalFileSystemStorageAdapter(StorageProperties properties) {
        this.properties = properties;
        this.allowedContentTypes = Set.copyOf(properties.allowedContentTypes());
        this.rootDirectory = Path.of(properties.rootDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create document storage directory: " + rootDirectory, e);
        }
    }

    @Override
    public StoredFile store(Long employeeId, String originalFilename, String contentType, long sizeBytes, InputStream content) {
        if (!allowedContentTypes.contains(contentType)) {
            throw new InvalidFileException("Unsupported content type: " + contentType);
        }
        if (sizeBytes <= 0 || sizeBytes > properties.maxFileSizeBytes()) {
            throw new InvalidFileException("File size must be between 1 byte and %d bytes"
                    .formatted(properties.maxFileSizeBytes()));
        }

        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        String storedFilename = UUID.randomUUID() + "." + extension;
        String relativePath = employeeId + "/" + storedFilename;

        Path targetPath = rootDirectory.resolve(relativePath).normalize();
        if (!targetPath.startsWith(rootDirectory)) {
            // Defense in depth: employeeId is a path-safe Long in practice,
            // but this guarantees a bug elsewhere can never write outside
            // the storage root.
            throw new InvalidFileException("Resolved storage path escapes the storage root");
        }

        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(content, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded file", e);
        }

        return new StoredFile(relativePath, sizeBytes);
    }

    @Override
    public Resource load(String storagePath) {
        Path targetPath = rootDirectory.resolve(storagePath).normalize();
        if (!targetPath.startsWith(rootDirectory)) {
            throw new InvalidFileException("Resolved storage path escapes the storage root");
        }
        try {
            return new UrlResource(targetPath.toUri());
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Could not resolve stored file: " + storagePath, e);
        }
    }

    @Override
    public void delete(String storagePath) {
        Path targetPath = rootDirectory.resolve(storagePath).normalize();
        if (!targetPath.startsWith(rootDirectory)) {
            throw new InvalidFileException("Resolved storage path escapes the storage root");
        }
        try {
            Files.deleteIfExists(targetPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete stored file: " + storagePath, e);
        }
    }
}
