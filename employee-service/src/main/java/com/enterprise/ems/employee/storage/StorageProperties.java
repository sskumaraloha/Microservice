package com.enterprise.ems.employee.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "employee.documents")
public record StorageProperties(String rootDirectory, long maxFileSizeBytes, List<String> allowedContentTypes) {

    private static final Set<String> DEFAULT_ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    public StorageProperties {
        if (rootDirectory == null || rootDirectory.isBlank()) {
            rootDirectory = "./employee-documents";
        }
        if (maxFileSizeBytes <= 0) {
            maxFileSizeBytes = 5 * 1024 * 1024; // 5 MB
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.copyOf(DEFAULT_ALLOWED_CONTENT_TYPES);
        }
    }
}
