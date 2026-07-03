# Employee Service

Employee CRUD, profile, documents, search, pagination, and sorting. Owns
`employee_db` exclusively.

Full lesson: [`docs/architecture/06-employee-service.md`](../docs/architecture/06-employee-service.md).

## Run locally

Requires Discovery Server, Config Server, and a MySQL instance for
`employee_db` (or run everything via Docker Compose below):
```
mvn -pl employee-service -am spring-boot:run
```
Swagger UI: http://localhost:8081/swagger-ui.html

## Run via Docker Compose

```
docker compose -f infra/docker/docker-compose.yml up --build
```

## Test

```
mvn -pl employee-service -am verify
```

The Testcontainers-backed `MySqlIntegrationTest` needs a Docker daemon; it
self-skips (does not fail) when none is available.

## Document storage

Uploaded documents are stored on a local filesystem path
(`employee.documents.root-directory`, defaults to
`/var/lib/ems/employee-documents` in Docker Compose). Only PDF, PNG, JPEG,
DOC, and DOCX are accepted, capped at 5 MB. See the Step 6 lesson for the
security reasoning.
