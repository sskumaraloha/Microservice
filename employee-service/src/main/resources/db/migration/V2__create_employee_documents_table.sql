CREATE TABLE employee_documents (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id    BIGINT       NOT NULL,
    document_type  VARCHAR(50)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    -- Where StoragePort actually persisted the bytes (a relative path
    -- under this service's storage root today; an object-store key/URL
    -- once Step 16's File Service takes over storage entirely).
    storage_path   VARCHAR(500) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    uploaded_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_employee_documents_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE
);

CREATE INDEX idx_employee_documents_employee_id ON employee_documents (employee_id);
