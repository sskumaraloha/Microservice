package com.enterprise.ems.employee.service;

import com.enterprise.ems.employee.domain.DocumentType;
import com.enterprise.ems.employee.dto.EmployeeDocumentResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface EmployeeDocumentService {

    EmployeeDocumentResponse upload(Long employeeId, DocumentType documentType, MultipartFile file);

    List<EmployeeDocumentResponse> list(Long employeeId);

    record Download(Resource resource, String filename, String contentType) {
    }

    Download download(Long employeeId, Long documentId);

    void delete(Long employeeId, Long documentId);
}
