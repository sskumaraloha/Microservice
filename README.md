# Enterprise Employee Management System — Microservices Course

A production-grade Employee Management System built as a teaching vehicle
for **Microservices Architecture with Java 21 and Spring Boot 3.x**.

This is not a "generate everything at once" project. It is built one
course step at a time, each step production-quality, tested, and
documented before the next one begins.

## Where to start

- [`COURSE-ROADMAP.md`](./COURSE-ROADMAP.md) — full list of steps, current
  progress, and what's next.
- [`docs/architecture/01-project-architecture.md`](./docs/architecture/01-project-architecture.md)
  — Step 1: overall architecture, service boundaries, folder structure,
  communication flow, and database design for the whole system.

## Tech stack

Java 21 · Spring Boot 3 · Spring Cloud · Spring Security · Spring Data JPA ·
MySQL · Redis · Kafka · RabbitMQ · Docker · Kubernetes · Helm · Prometheus ·
Grafana · ELK · Zipkin · OpenFeign · Resilience4j · JWT · OAuth2 · OpenAPI ·
JUnit · Mockito · TestContainers · GitHub Actions · Flyway · MapStruct ·
Lombok.

## Services

| Service | Status | Owns |
|---|---|---|
| Discovery Server | **done** (Step 2) | service registry |
| Config Server | **done** (Step 3) | centralized configuration |
| API Gateway | **done** (Step 4) | routing, auth enforcement, rate limiting |
| Auth Service | **done** (Step 5) | identity, JWT, RBAC, OAuth2 (`auth_db`) |
| Employee Service | planned (Step 6) | employee data (`employee_db`) |
| Department Service | planned (Step 7) | org structure (`department_db`) |
| Notification Service | planned (Step 14) | email/SMS on domain events |

Each service is independently deployable, owns its own database, and
communicates with others only through APIs or events — never through a
shared database. See Step 1's lesson for the reasoning behind every one of
these decisions.
