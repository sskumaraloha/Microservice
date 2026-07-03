# Enterprise Microservices Course — Roadmap

This repository is built **one step at a time**. Each step adds one working,
production-quality piece of the system and is merged only after review.
Do not jump ahead — this file is the single source of truth for what is
done, in progress, or upcoming.

## How to read this file

- `[x]` = implemented, tested, documented
- `[ ]` = not started yet
- Steps are deliberately small. A step is "done" when it has: working code,
  tests, Swagger docs (where applicable), and a corresponding lesson under
  `docs/architecture/`.

## Phase 0 — Foundations

- [x] **Step 1 — Project Architecture & Repository Skeleton**
      (`docs/architecture/01-project-architecture.md`)

## Phase 1 — Platform Infrastructure

- [x] **Step 2 — Discovery Server (Eureka)**
      (`docs/architecture/02-discovery-server.md`)
- [x] **Step 3 — Config Server (centralized configuration)**
      (`docs/architecture/03-config-server.md`)
- [ ] Step 4 — API Gateway (Spring Cloud Gateway, routing, rate limiting)

## Phase 2 — Core Business Services

- [ ] Step 5 — Authentication Service (JWT, refresh tokens, RBAC, OAuth2 Google, OTP)
- [ ] Step 6 — Employee Service (CRUD, search, pagination, documents)
- [ ] Step 7 — Department Service (hierarchy, statistics, employee assignment)

## Phase 3 — Cross-Cutting Concerns

- [ ] Step 8 — Inter-service communication (OpenFeign, WebClient) + Resilience4j
- [ ] Step 9 — Event-driven architecture (Kafka/RabbitMQ, Outbox Pattern, Saga)
- [ ] Step 10 — Caching (Redis)
- [ ] Step 11 — Observability (Zipkin/OpenTelemetry tracing, correlation IDs)
- [ ] Step 12 — Monitoring (Prometheus + Grafana, Actuator metrics)
- [ ] Step 13 — Centralized logging (ELK stack)

## Phase 4 — Additional Services

- [ ] Step 14 — Notification Service
- [ ] Step 15 — Audit Service
- [ ] Step 16 — File Service

## Phase 5 — Testing & Quality

- [ ] Step 17 — Unit + integration testing strategy (JUnit, Mockito, TestContainers)
- [ ] Step 18 — Contract testing

## Phase 6 — DevOps & Delivery

- [ ] Step 19 — Dockerize every service, Docker Compose for local dev
- [ ] Step 20 — Kubernetes manifests (Deployment, Service, Ingress, ConfigMap, Secret, HPA)
- [ ] Step 21 — Helm charts
- [ ] Step 22 — CI/CD with GitHub Actions

## Phase 7 — Security Hardening

- [ ] Step 23 — OWASP top 10 review, secure headers, CORS, rate limiting

## Phase 8 — Performance

- [ ] Step 24 — Database optimization, HikariCP tuning, JVM tuning

---

**Current status:** Step 3 complete. Proceeding to Step 4 (API Gateway).
