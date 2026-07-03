CREATE TABLE employees (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    phone          VARCHAR(30),
    position       VARCHAR(100),
    -- Not a foreign key: department_id is an ID owned by Department
    -- Service, a completely separate database. Cross-service referential
    -- integrity is enforced in application code (Step 8's Feign call), not
    -- by the database — see docs/architecture/01-project-architecture.md.
    department_id  BIGINT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    hire_date      DATE         NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_employees_email UNIQUE (email)
);

CREATE INDEX idx_employees_department_id ON employees (department_id);
CREATE INDEX idx_employees_last_name ON employees (last_name);
CREATE INDEX idx_employees_status ON employees (status);
