CREATE TABLE departments (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                  VARCHAR(150) NOT NULL,
    code                  VARCHAR(30)  NOT NULL,
    description           VARCHAR(500),
    -- Self-referencing: the hierarchy lives entirely inside this service's
    -- own table, unlike employees.department_id (Step 6), which points at
    -- a row owned by a completely different database.
    parent_department_id BIGINT,
    -- Not a foreign key: the employee it names is owned by Employee
    -- Service's database. Same cross-service-reference rule as
    -- employees.department_id.
    manager_employee_id  BIGINT,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_departments_name UNIQUE (name),
    CONSTRAINT uq_departments_code UNIQUE (code),
    -- RESTRICT (the default): deleting a department with existing children
    -- must fail loudly rather than cascade-deleting a whole subtree or
    -- silently orphaning rows.
    CONSTRAINT fk_departments_parent FOREIGN KEY (parent_department_id) REFERENCES departments (id)
);

CREATE INDEX idx_departments_parent_department_id ON departments (parent_department_id);
