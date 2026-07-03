# Department Service

Department CRUD, organizational hierarchy (self-referencing tree with
cycle prevention), and local statistics. Owns `department_db` exclusively.

Full lesson: [`docs/architecture/07-department-service.md`](../docs/architecture/07-department-service.md).

## Run locally

Requires Discovery Server, Config Server, and a MySQL instance for
`department_db` (or run everything via Docker Compose below):
```
mvn -pl department-service -am spring-boot:run
```
Swagger UI: http://localhost:8082/swagger-ui.html

## Run via Docker Compose

```
docker compose -f infra/docker/docker-compose.yml up --build
```

## Test

```
mvn -pl department-service -am verify
```

The Testcontainers-backed `MySqlIntegrationTest` needs a Docker daemon; it
self-skips (does not fail) when none is available.

## Note on statistics

`/api/v1/departments/{id}/statistics` reports hierarchy-only numbers
(direct children, total descendants, depth). Employee headcount is
deliberately not included yet — see the Step 7 lesson for why.
