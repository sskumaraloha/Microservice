package com.enterprise.ems.employee.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * Employee Service stores document bytes itself for now. Step 16 (File
 * Service) will replace {@link LocalFileSystemStorageAdapter} with a client
 * of that dedicated service (or an object-store adapter) without any
 * caller of this port needing to change — that is the point of depending
 * on an interface here instead of a concrete filesystem/S3 client directly.
 */
public interface StoragePort {

    /**
     * @throws com.enterprise.ems.employee.exception.InvalidFileException if the content type is not
     *                                                                    allow-listed or the size exceeds the configured limit
     */
    StoredFile store(Long employeeId, String originalFilename, String contentType, long sizeBytes, InputStream content);

    Resource load(String storagePath);

    void delete(String storagePath);
}
