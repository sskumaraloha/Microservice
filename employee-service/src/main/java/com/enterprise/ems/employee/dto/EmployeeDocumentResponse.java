package com.enterprise.ems.employee.dto;

import com.enterprise.ems.employee.domain.DocumentType;

import java.time.Instant;

public record EmployeeDocumentResponse(
        Long id,
        Long employeeId,
        DocumentType documentType,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt
) {
}
