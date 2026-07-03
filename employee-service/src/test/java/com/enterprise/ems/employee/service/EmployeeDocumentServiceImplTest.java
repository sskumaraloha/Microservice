package com.enterprise.ems.employee.service;

import com.enterprise.ems.employee.domain.DocumentType;
import com.enterprise.ems.employee.domain.Employee;
import com.enterprise.ems.employee.domain.EmployeeDocument;
import com.enterprise.ems.employee.dto.EmployeeDocumentResponse;
import com.enterprise.ems.employee.exception.EmployeeDocumentNotFoundException;
import com.enterprise.ems.employee.exception.EmployeeNotFoundException;
import com.enterprise.ems.employee.mapper.EmployeeMapper;
import com.enterprise.ems.employee.mapper.EmployeeMapperImpl;
import com.enterprise.ems.employee.repository.EmployeeDocumentRepository;
import com.enterprise.ems.employee.repository.EmployeeRepository;
import com.enterprise.ems.employee.service.impl.EmployeeDocumentServiceImpl;
import com.enterprise.ems.employee.storage.StoragePort;
import com.enterprise.ems.employee.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeDocumentServiceImplTest {

    private EmployeeRepository employeeRepository;
    private EmployeeDocumentRepository documentRepository;
    private StoragePort storagePort;
    private final EmployeeMapper employeeMapper = new EmployeeMapperImpl();
    private EmployeeDocumentService documentService;
    private Employee employee;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        documentRepository = mock(EmployeeDocumentRepository.class);
        storagePort = mock(StoragePort.class);
        documentService = new EmployeeDocumentServiceImpl(employeeRepository, documentRepository, storagePort, employeeMapper);

        employee = new Employee();
        employee.setId(1L);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uploadRejectsAnUnknownEmployeeBeforeTouchingStorage() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        assertThatThrownBy(() -> documentService.upload(99L, DocumentType.RESUME, file))
                .isInstanceOf(EmployeeNotFoundException.class);
        verify(storagePort, never()).store(anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void uploadStoresTheFileAndPersistsMetadata() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(storagePort.store(eq(1L), any(), eq("application/pdf"), eq(7L), any()))
                .thenReturn(new StoredFile("1/generated-name.pdf", 7L));
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        EmployeeDocumentResponse response = documentService.upload(1L, DocumentType.RESUME, file);

        assertThat(response.employeeId()).isEqualTo(1L);
        assertThat(response.documentType()).isEqualTo(DocumentType.RESUME);

        ArgumentCaptor<EmployeeDocument> captor = ArgumentCaptor.forClass(EmployeeDocument.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getStoragePath()).isEqualTo("1/generated-name.pdf");
    }

    @Test
    void listThrowsForAnUnknownEmployee() {
        when(employeeRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> documentService.list(99L)).isInstanceOf(EmployeeNotFoundException.class);
    }

    @Test
    void downloadThrowsWhenTheDocumentBelongsToADifferentEmployee() {
        Employee otherEmployee = new Employee();
        otherEmployee.setId(2L);
        EmployeeDocument document = new EmployeeDocument();
        document.setId(5L);
        document.setEmployee(otherEmployee);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> documentService.download(1L, 5L))
                .isInstanceOf(EmployeeDocumentNotFoundException.class);
    }

    @Test
    void downloadReturnsTheStoredResourceAndOriginalFilename() {
        EmployeeDocument document = new EmployeeDocument();
        document.setId(5L);
        document.setEmployee(employee);
        document.setStoragePath("1/generated-name.pdf");
        document.setOriginalFilename("resume.pdf");
        document.setContentType("application/pdf");
        when(documentRepository.findById(5L)).thenReturn(Optional.of(document));
        Resource resource = mock(Resource.class);
        when(storagePort.load("1/generated-name.pdf")).thenReturn(resource);

        EmployeeDocumentService.Download download = documentService.download(1L, 5L);

        assertThat(download.filename()).isEqualTo("resume.pdf");
        assertThat(download.resource()).isSameAs(resource);
    }

    @Test
    void deleteRemovesBothTheStoredFileAndTheDatabaseRow() {
        EmployeeDocument document = new EmployeeDocument();
        document.setId(5L);
        document.setEmployee(employee);
        document.setStoragePath("1/generated-name.pdf");
        when(documentRepository.findById(5L)).thenReturn(Optional.of(document));

        documentService.delete(1L, 5L);

        verify(storagePort).delete("1/generated-name.pdf");
        verify(documentRepository).delete(document);
    }

    @Test
    void listReturnsAllDocumentsForAnExistingEmployee() {
        when(employeeRepository.existsById(1L)).thenReturn(true);
        EmployeeDocument document = new EmployeeDocument();
        document.setId(5L);
        document.setEmployee(employee);
        document.setDocumentType(DocumentType.CONTRACT);
        when(documentRepository.findByEmployeeId(1L)).thenReturn(List.of(document));

        List<EmployeeDocumentResponse> responses = documentService.list(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).documentType()).isEqualTo(DocumentType.CONTRACT);
    }
}
