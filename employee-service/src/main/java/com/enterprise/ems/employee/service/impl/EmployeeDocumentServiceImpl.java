package com.enterprise.ems.employee.service.impl;

import com.enterprise.ems.employee.domain.DocumentType;
import com.enterprise.ems.employee.domain.Employee;
import com.enterprise.ems.employee.domain.EmployeeDocument;
import com.enterprise.ems.employee.dto.EmployeeDocumentResponse;
import com.enterprise.ems.employee.exception.EmployeeDocumentNotFoundException;
import com.enterprise.ems.employee.exception.EmployeeNotFoundException;
import com.enterprise.ems.employee.exception.InvalidFileException;
import com.enterprise.ems.employee.mapper.EmployeeMapper;
import com.enterprise.ems.employee.repository.EmployeeDocumentRepository;
import com.enterprise.ems.employee.repository.EmployeeRepository;
import com.enterprise.ems.employee.service.EmployeeDocumentService;
import com.enterprise.ems.employee.storage.StoragePort;
import com.enterprise.ems.employee.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Service
public class EmployeeDocumentServiceImpl implements EmployeeDocumentService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeDocumentRepository documentRepository;
    private final StoragePort storagePort;
    private final EmployeeMapper employeeMapper;

    public EmployeeDocumentServiceImpl(EmployeeRepository employeeRepository,
                                        EmployeeDocumentRepository documentRepository,
                                        StoragePort storagePort,
                                        EmployeeMapper employeeMapper) {
        this.employeeRepository = employeeRepository;
        this.documentRepository = documentRepository;
        this.storagePort = storagePort;
        this.employeeMapper = employeeMapper;
    }

    @Override
    @Transactional
    public EmployeeDocumentResponse upload(Long employeeId, DocumentType documentType, MultipartFile file) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

        if (file.isEmpty()) {
            throw new InvalidFileException("Uploaded file must not be empty");
        }

        StoredFile stored;
        try {
            stored = storagePort.store(employeeId, file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }

        EmployeeDocument document = new EmployeeDocument();
        document.setEmployee(employee);
        document.setDocumentType(documentType);
        document.setOriginalFilename(sanitizeForDisplay(file.getOriginalFilename()));
        document.setStoragePath(stored.storagePath());
        document.setContentType(file.getContentType());
        document.setSizeBytes(stored.sizeBytes());

        return employeeMapper.toDocumentResponse(documentRepository.save(document));
    }

    @Override
    public List<EmployeeDocumentResponse> list(Long employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new EmployeeNotFoundException(employeeId);
        }
        return documentRepository.findByEmployeeId(employeeId).stream()
                .map(employeeMapper::toDocumentResponse)
                .toList();
    }

    @Override
    public Download download(Long employeeId, Long documentId) {
        EmployeeDocument document = findOrThrow(employeeId, documentId);
        return new Download(storagePort.load(document.getStoragePath()), document.getOriginalFilename(), document.getContentType());
    }

    @Override
    @Transactional
    public void delete(Long employeeId, Long documentId) {
        EmployeeDocument document = findOrThrow(employeeId, documentId);
        storagePort.delete(document.getStoragePath());
        documentRepository.delete(document);
    }

    private EmployeeDocument findOrThrow(Long employeeId, Long documentId) {
        EmployeeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new EmployeeDocumentNotFoundException(employeeId, documentId));
        if (!document.getEmployee().getId().equals(employeeId)) {
            throw new EmployeeDocumentNotFoundException(employeeId, documentId);
        }
        return document;
    }

    /** Strips any path separators from a client-supplied filename before it is ever displayed or logged. */
    private String sanitizeForDisplay(String originalFilename) {
        if (originalFilename == null) {
            return "unnamed";
        }
        return originalFilename.replaceAll("[/\\\\]", "_");
    }
}
