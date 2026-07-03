package com.enterprise.ems.employee.controller;

import com.enterprise.ems.employee.domain.DocumentType;
import com.enterprise.ems.employee.dto.EmployeeDocumentResponse;
import com.enterprise.ems.employee.service.EmployeeDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Employee Documents")
@RestController
@RequestMapping("/api/v1/employees/{employeeId}/documents")
public class EmployeeDocumentController {

    private final EmployeeDocumentService documentService;

    public EmployeeDocumentController(EmployeeDocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "Upload a document for an employee")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeDocumentResponse upload(@PathVariable Long employeeId,
                                            @RequestParam DocumentType documentType,
                                            @RequestParam MultipartFile file) {
        return documentService.upload(employeeId, documentType, file);
    }

    @Operation(summary = "List an employee's documents")
    @GetMapping
    public List<EmployeeDocumentResponse> list(@PathVariable Long employeeId) {
        return documentService.list(employeeId);
    }

    @Operation(summary = "Download a document")
    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long employeeId, @PathVariable Long documentId) {
        EmployeeDocumentService.Download download = documentService.download(employeeId, documentId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename()).build().toString())
                .body(download.resource());
    }

    @Operation(summary = "Delete a document")
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long employeeId, @PathVariable Long documentId) {
        documentService.delete(employeeId, documentId);
    }
}
