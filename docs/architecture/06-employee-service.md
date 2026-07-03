# Step 6 — Employee Service

## 1. Goal

Build the first service that manages real business data rather than
platform infrastructure: employee CRUD, profile fields, document uploads,
and search with pagination and sorting — owning `employee_db` exclusively.
This is also the first service to demonstrate flexible, composable search
(any combination of filters) without a combinatorial explosion of
repository methods, and the first to handle untrusted file uploads, which
means confronting real file-upload security rather than a toy
"just save the bytes" implementation.

## 2. Architecture Diagram

```
                        API Gateway (verifies JWT, adds X-Auth-*)
                                       │
                                       ▼
                              Employee Service
   ┌────────────────────────────────────────────────────────────────┐
   │  HeaderAuthenticationFilter (trusts X-Auth-*, same as Step 5)     │
   │                          │                                        │
   │  EmployeeController          EmployeeDocumentController             │
   │  CRUD + search/page/sort     upload / list / download / delete      │
   │         │                              │                             │
   │  EmployeeService              EmployeeDocumentService                 │
   │  (Specification-based           │                                     │
   │   dynamic search)         StoragePort (interface)                      │
   │         │                        │                                      │
   │         ▼                        ▼                                      │
   │    employee_db          LocalFileSystemStorageAdapter                    │
   │    (MySQL)               - allow-listed content types                    │
   │                           - size limit enforced                           │
   │                           - random filename on disk, never                │
   │                             the client-supplied name                      │
   └────────────────────────────────────────────────────────────────────────┘
```

`department_id` on the `employees` table is a plain `BIGINT`, deliberately
**not** a foreign key — Department Service (Step 7) owns its own database.
Cross-service referential integrity for that field arrives in Step 8 (an
OpenFeign call to Department Service), not from the database.

## 3. Folder Structure

```
employee-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/employee/
    │   │   ├── EmployeeServiceApplication.java
    │   │   ├── domain/       # Employee, EmployeeDocument, EmployeeStatus, DocumentType
    │   │   ├── repository/   # EmployeeRepository (+ JpaSpecificationExecutor), EmployeeDocumentRepository
    │   │   ├── dto/          # EmployeeRequest/Response, search criteria, document DTOs
    │   │   ├── mapper/       # MapStruct EmployeeMapper
    │   │   ├── service/
    │   │   │   └── impl/     # EmployeeServiceImpl, EmployeeDocumentServiceImpl, EmployeeSpecifications
    │   │   ├── storage/      # StoragePort + LocalFileSystemStorageAdapter (Step 16 will add an S3/File-Service adapter)
    │   │   ├── controller/   # EmployeeController, EmployeeDocumentController
    │   │   ├── security/     # HeaderAuthenticationFilter (duplicated from Step 5, by design)
    │   │   ├── exception/    # domain exceptions + @RestControllerAdvice
    │   │   └── config/       # SecurityConfig, OpenApiConfig
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/ # V1 employees, V2 employee_documents
    └── test/
        ├── java/com/enterprise/ems/employee/
        │   ├── service/       # Mockito unit tests (CRUD + documents)
        │   ├── storage/       # LocalFileSystemStorageAdapter security tests
        │   ├── controller/    # @WebMvcTest slice
        │   └── integration/   # H2 full-context + Testcontainers MySQL
        └── resources/application-test.yml
```

## 4. Explanation

### Dynamic search without a combinatorial explosion

`EmployeeController.search` accepts four independent, optional filters
(name, email, department, status) plus `Pageable` (page/size/sort — all
free from Spring Data). Handling every filter combination with derived
query methods (`findByStatusAndDepartmentId`, `findByNameContainingAndStatus`,
...) would require 2⁴ methods for four optional filters, growing
exponentially with each new filter. `JpaSpecificationExecutor` plus a
small `EmployeeSpecifications.matching()` builder instead composes exactly
the predicates that apply, using **one** query method
(`findAll(Specification, Pageable)`) for every combination — including
zero filters (browse-all) and all four at once.

### File upload security: three OWASP-relevant decisions

An HR document upload endpoint is exactly the kind of feature OWASP's Top
10 has a specific entry for (Unrestricted File Upload). Three deliberate
decisions in `LocalFileSystemStorageAdapter`:

1. **Content-type allow-list, not a deny-list.** Only PDF, PNG, JPEG, DOC,
   and DOCX are accepted; everything else — including executables and
   scripts — is rejected before a single byte is written to disk.
2. **The stored filename is never derived from client input.** A crafted
   filename like `../../etc/passwd` or `resume.pdf.sh` can't do anything,
   because the actual on-disk name is always a fresh `UUID` plus an
   extension pulled from a fixed content-type → extension map — the
   client's original filename is stored only as display metadata in the
   database, never used to build a filesystem path.
3. **A hard size limit**, enforced at both the Spring MVC multipart layer
   (`spring.servlet.multipart.max-file-size`) and again inside the storage
   adapter itself (defense in depth — a service-specific business rule
   shouldn't only live in a framework-level HTTP setting).

### `StoragePort`: an interface today so Step 16 is a swap, not a rewrite

Every caller depends on `StoragePort`, not on
`LocalFileSystemStorageAdapter` directly. When Step 16 introduces a
dedicated File Service (or S3/object storage), only a new adapter needs
to be written and wired in — `EmployeeDocumentServiceImpl`,
`EmployeeDocumentController`, and every test that doesn't test the
adapter directly are completely unaffected. This is the same "depend on
the interface, not the implementation" pattern Step 5's `NotificationPort`
established for email/SMS.

### RBAC: read is broad, write is narrow

`SecurityConfig` allows any authenticated caller to `GET`, but restricts
writes (create, update, status change, delete, document upload/delete) to
`ADMIN`/`MANAGER`. An `EMPLOYEE` can look up a colleague's profile but
can't edit HR records. This is intentionally coarse — true resource-level
rules ("a manager may only edit employees in their own department")
require knowing department membership, which lives in a different
service's database and isn't available here until the Feign integration
later in the course.

### A real bug this step caught: the missing `-parameters` compiler flag

Every controller method in this service uses `@PathVariable Long id` and
similar **without** an explicit name (`@PathVariable("id")`). Spring MVC
resolves the binding by reading the compiled parameter name via
reflection — which is only present in the `.class` file if compiled with
`javac -parameters`. `spring-boot-starter-parent` sets this by default;
this project's root POM, which only imports `spring-boot-dependencies`
as a BOM rather than inheriting from the starter parent, did not. The
first time this project used bare `@PathVariable`/`@RequestParam` names
(Steps 1-5 happened not to need it), every such endpoint failed at
runtime with `IllegalArgumentException: Name for argument of type
[java.lang.Long] not specified...` — caught immediately by this step's
own integration tests, not silently shipped. Fixed once, in the parent
POM's `maven-compiler-plugin` configuration
(`<parameters>true</parameters>`), for every module at once.

## 5. Implementation

Module: `employee-service/`, added to the root `pom.xml` `<modules>`.

- **Flyway migrations** — `employees` (with `uq_employees_email`, indexes
  on `department_id`/`last_name`/`status`) and `employee_documents`
  (foreign key to `employees`, cascade delete).
- **`EmployeeRepository`** extends both `JpaRepository` and
  `JpaSpecificationExecutor` for the dynamic search described above.
- **`EmployeeServiceImpl`** — CRUD with duplicate-email checks on both
  create and update (excluding the record's own id via
  `existsByEmailAndIdNot`), status transitions, and specification-driven
  search.
- **`StoragePort`/`LocalFileSystemStorageAdapter`/`EmployeeDocumentServiceImpl`** —
  upload/list/download/delete, with the security properties above.
- **`HeaderAuthenticationFilter`** — byte-for-byte the same pattern as
  Auth Service's (Step 5), duplicated rather than shared (see that step's
  lesson for why: true independent deployability over DRY across service
  boundaries).
- **`GlobalExceptionHandler`** — 404 (employee/document not found), 409
  (duplicate email), 400 (invalid file, validation failures).
- **`config-repo/employee-service.yml`** (Config Server) now also carries
  `employee.documents.*` (root directory, max size, allowed content
  types) — business configuration, same placement rule established in
  Step 3.

## 6. Testing

**33 tests, all passing** (32 executed, 1 self-skipped without Docker):

- `EmployeeServiceImplTest` (9) — create/duplicate-email, not-found on
  get/delete, specification-based search delegation, update with a
  conflicting email, status update, delete.
- `EmployeeDocumentServiceImplTest` (7) — upload rejects an unknown
  employee before touching storage, upload persists correct metadata,
  list/download/delete including the specific case of a document
  belonging to a *different* employee (must 404, not leak another
  employee's file).
- `LocalFileSystemStorageAdapterTest` (4) — a path-traversal-style
  filename never appears in the stored path, a disallowed content type is
  rejected, an oversized file is rejected, delete actually removes the
  file from disk.
- `EmployeeControllerTest` (6, `@WebMvcTest`) — status codes and
  validation-error shapes independent of business logic.
- `EmployeeServiceApplicationIntegrationTest` (6, full context, H2 in
  MySQL-compatibility mode) — full CRUD lifecycle over real HTTP,
  duplicate-email 409, search with filtering + pagination + sorting
  (verifying exact ordering and page size), the complete
  upload → list → download → delete document flow, a rejected upload for
  a disallowed content type, and all three RBAC cases (unauthenticated,
  wrong role, correct role).
- `MySqlIntegrationTest` (1, `@Testcontainers(disabledWithoutDocker = true)`) —
  re-validates the migrations and a basic create/read against real MySQL;
  self-skips here for the same reason as Auth Service's equivalent class.

```
mvn -pl employee-service -am verify
```

One real bug surfaced and fixed while writing these tests: the first
version of the integration test used `TestRestTemplate`'s shorthand
methods (`postForEntity`, `put`, `delete`), which — unlike `exchange()` —
**do not accept custom headers**. Every "admin" request in that draft was
silently sent unauthenticated, and every assertion failed with 403
instead of the expected success status. Fixed by routing every call
through `exchange()` with explicit trusted-header simulation. This is
listed as a Common Mistake below because it is an easy trap: the test
looked reasonable and compiled fine, and only failed once actually run.

## 7. Interview Questions

1. **Why use `JpaSpecificationExecutor` instead of Spring Data derived
   query methods for a multi-filter search?**
   *Look for:* avoiding a combinatorial explosion of methods for optional
   filter combinations; one method handles all of them.

2. **What are the three most important defenses against a malicious file
   upload, and why does "check the file extension" not make the list?**
   *Look for:* content-type allow-list, never trusting the client's
   filename for the storage path, and a size limit — a file extension is
   trivially spoofable and provides no real security on its own.

3. **Why does `StoragePort` exist as an interface with only one real
   implementation today?**
   *Look for:* the Dependency Inversion Principle in service, preparing
   for a documented future swap (File Service/object storage) without
   touching any caller — not speculative over-engineering, since the swap
   is explicitly planned (Step 16).

4. **Why is `department_id` a plain column instead of a foreign key?**
   *Look for:* database-per-service — Department Service's database is a
   different physical database entirely; foreign keys can't span
   databases, and even if they technically could via cross-database
   queries, doing so would recouple two services that are supposed to be
   independently deployable.

5. **What does `-parameters` do, and why did this specific service catch
   its absence when four earlier services didn't?**
   *Look for:* it preserves method parameter names in bytecode so
   Spring can resolve `@PathVariable`/`@RequestParam` bindings without an
   explicit name; earlier services simply didn't have unnamed
   path variables in their controllers yet.

## 8. Best Practices

- Push filter composition into the persistence layer (`Specification`)
  rather than fetching everything and filtering in Java — it keeps
  pagination and sorting correct and avoids loading unbounded result sets.
- Treat every user-controlled string (filename, content type) as hostile
  input when it influences a filesystem path.
- Depend on ports/interfaces for anything with a known future
  implementation swap, not just "in case we need it someday."
- When a `TestRestTemplate` test "just returns the wrong status," check
  whether a shorthand method silently dropped headers before suspecting
  the application code.

## 9. Common Mistakes

- Adding a new derived-query method every time a new optional search
  filter is requested, instead of stepping back to a `Specification`.
- Trusting a client-supplied filename or extension anywhere in a storage
  path.
- Assuming `TestRestTemplate.postForEntity`/`.put`/`.delete` carry
  whatever headers were set on an earlier call — they don't; only
  `.exchange()` accepts headers per call.
- Forgetting `-parameters` (or explicit `@PathVariable("name")`
  annotations) when a project doesn't inherit from
  `spring-boot-starter-parent`.

## 10. Summary

Employee Service now owns real business data end to end: CRUD, flexible
search with pagination and sorting, and a security-conscious document
upload pipeline — all trusting the Gateway's identity headers rather than
re-verifying tokens, consistent with every service built from Step 5
onward.

**Next step (Step 7): Department Service** — CRUD, hierarchy, and
statistics, owning `department_db`; the first service whose statistics
will eventually be kept in sync via events from Employee Service (Step 9)
rather than a live cross-service query for every request.
