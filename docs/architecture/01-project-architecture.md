# Step 1 — Project Architecture & Repository Skeleton

## 1. Goal

Before writing a single line of business logic, establish:

1. What the overall system looks like (all services + infrastructure).
2. Why it is decomposed this way (service boundaries).
3. How services will talk to each other (sync vs async).
4. How data is owned (database-per-service).
5. The repository layout the whole course will build into.

Everything after this step is an incremental fill-in of this skeleton.
No shortcuts, no "big bang" generation — one brick at a time.

---

## 2. Architecture Diagram (ASCII)

```
                                   ┌─────────────────────────┐
                                   │        Clients          │
                                   │  (Web, Mobile, Postman) │
                                   └────────────┬─────────────┘
                                                │ HTTPS
                                                ▼
                              ┌────────────────────────────────┐
                              │           API GATEWAY           │
                              │   Spring Cloud Gateway           │
                              │  - Routing                       │
                              │  - JWT validation                │
                              │  - Rate limiting                 │
                              │  - CORS / Security headers        │
                              └───────────────┬──────────────────┘
                                              │
                     ┌────────────────────────┼─────────────────────────┐
                     ▼                        ▼                         ▼
          ┌─────────────────┐      ┌───────────────────┐      ┌────────────────────┐
          │ Auth Service      │      │ Employee Service   │      │ Department Service  │
          │ (auth_db)         │      │ (employee_db)       │      │ (department_db)      │
          └─────────┬─────────┘      └──────────┬──────────┘      └──────────┬───────────┘
                    │                             │                            │
                    │        REST / OpenFeign (sync)   Kafka / RabbitMQ (async)│
                    └─────────────────────────────┴────────────────────────────┘

        ┌───────────────────────────────────────────────────────────────────────┐
        │                         PLATFORM INFRASTRUCTURE                        │
        │                                                                        │
        │  Discovery Server (Eureka)   Config Server        Redis (cache)         │
        │  Kafka / RabbitMQ broker     Zipkin / OTel         Prometheus + Grafana  │
        │  ELK (Elasticsearch/Logstash/Kibana)               MySQL (per service)  │
        └───────────────────────────────────────────────────────────────────────┘
```

Key idea: the **Gateway is the only door** clients use. Every service
registers itself with **Discovery Server** and pulls its configuration from
**Config Server**. Services never call each other's database — only APIs or
events.

---

## 3. Folder Structure (Monorepo)

We use a **monorepo** (all services in one Git repository) for this course
because it is easier to teach and review end-to-end. In a real organization
each service would very likely live in its own repository with its own
CI/CD pipeline and access control — we call this out explicitly as a
trade-off below.

```
Microservice/
├── pom.xml                          # Maven parent (dependency management only)
├── README.md
├── COURSE-ROADMAP.md                # tracks course progress, step by step
├── docs/
│   └── architecture/
│       └── 01-project-architecture.md   # this file
├── infra/
│   └── docker/
│       └── docker-compose.yml       # local dev: MySQL, Redis, Kafka, Zipkin...
├── discovery-server/                # Step 2  — Eureka
├── config-server/                   # Step 3  — centralized config
├── api-gateway/                     # Step 4  — Spring Cloud Gateway
├── auth-service/                    # Step 5  — JWT / OAuth2 / RBAC
├── employee-service/                # Step 6  — Employee CRUD
├── department-service/              # Step 7  — Department CRUD
└── notification-service/            # Step 14 — email/SMS notifications
```

Each service module will, once implemented, follow this internal layout
(Hexagonal-flavored layered architecture):

```
employee-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/employee/
    │   │   ├── EmployeeServiceApplication.java
    │   │   ├── controller/        # REST controllers (inbound adapters)
    │   │   ├── service/           # use cases / business logic (application core)
    │   │   │   └── impl/
    │   │   ├── repository/        # Spring Data JPA (outbound adapters)
    │   │   ├── domain/            # JPA entities
    │   │   ├── dto/               # request/response records
    │   │   ├── mapper/            # MapStruct mappers (entity <-> dto)
    │   │   ├── exception/         # domain exceptions + @ControllerAdvice
    │   │   ├── config/            # security, OpenAPI, beans
    │   │   └── validation/        # custom validators
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/      # Flyway SQL migrations
    └── test/
        └── java/com/enterprise/ems/employee/
            ├── controller/        # @WebMvcTest
            ├── service/           # Mockito unit tests
            └── integration/       # TestContainers integration tests
```

Why this shape:
- **controller** = inbound port/adapter (HTTP in).
- **service** = the application core / use cases — no framework/HTTP/JPA
  types leak in here beyond what's necessary.
- **repository** = outbound port/adapter (persistence out).
- **domain vs dto** = we never expose JPA entities over the wire. Entities
  model persistence; DTOs model the API contract. MapStruct converts
  between them at compile time (no reflection cost, no runtime surprises).

---

## 4. Why These Microservices Exist

### Monolith vs Microservices (the real trade-off)

A monolith is **one deployable unit** containing Employee, Department, and
Auth logic in one codebase, one database, one JVM.

| | Monolith | Microservices |
|---|---|---|
| Deployment | One deploy for any change | Deploy each service independently |
| Scaling | Scale the whole app | Scale only the hot service (e.g. Employee reads) |
| Team ownership | One team, or many teams stepping on each other | One team per service (Conway's Law) |
| Failure isolation | One bug can crash everything | A crash in Notification doesn't take down Auth |
| Technology | One stack for everything | Each service can pick its own stack |
| Complexity | Low operational complexity, high code coupling | High operational complexity (network, observability), low code coupling |
| Data consistency | Easy — one DB, ACID transactions | Hard — distributed transactions, eventual consistency (Saga) |

**Real-world analogy:** A monolith is a single restaurant kitchen where one
overloaded station (say, the grill) slows down every ticket, even dessert
orders. Microservices are separate stations (grill, salad, dessert, drinks)
that can be staffed and scaled independently — but now you need a
coordination system (the expediter = API Gateway) so plates leave the
kitchen correctly assembled.

We are building microservices here **for the learning value**, not because
this specific app has the scale to justify it (an actual employee
management system for 500 employees would likely be fine as a modular
monolith). This is a deliberate and important lesson: **microservices are
an organizational and scaling tool, not a default architecture.** Companies
like Amazon/Netflix/Uber adopted them because hundreds of independent teams
needed to ship without blocking each other and because different
subsystems had wildly different scaling profiles.

### Service Decomposition — why these three, and where the lines are

We decompose by **bounded context** (Domain-Driven Design), not by
technical layer:

- **Employee Service** owns everything about *who an employee is*: profile,
  documents, employment status. It does NOT own "which department" beyond
  a `departmentId` reference — it does not own department hierarchy.
- **Department Service** owns *organizational structure*: departments,
  hierarchy, statistics (headcount etc. computed from Employee Service via
  API/events, not by joining tables).
- **Auth Service** owns *identity and access*: who can log in, what role
  they have, tokens. It knows nothing about employee HR data.

This separation mirrors how a real HR platform is organized: an "Identity"
team, an "HR core" team, and an "Org structure" team can all ship
independently as long as they agree on the API contract between them.

### Database per Service — and why a shared database is an anti-pattern

Each service owns its schema exclusively; no other service is allowed to
read/write it directly.

**Why a shared database is tempting (and dangerous):**
- Tempting: one JOIN gets you employee + department + user info instantly.
- Dangerous: it silently re-couples services at the data layer. Now
  Department Service's migration can break Employee Service in production.
  You can no longer deploy or scale services independently — you've built
  a distributed monolith (all the operational cost of microservices, none
  of the independence benefit).

**The cost we accept instead:** no cross-service SQL joins. If Department
Service needs "how many employees are in department X," it either:
1. Calls Employee Service's API synchronously (OpenFeign), or
2. Maintains a local, eventually-consistent read model kept up to date via
   Kafka events published by Employee Service (this is the pattern we'll
   implement in Step 9 — CQRS-lite).

---

## 5. Communication Flow — a concrete example

**Scenario: HR onboards a new employee and assigns them to Engineering.**

Synchronous path (client-facing, needs an immediate answer):
```
Client → API Gateway → Employee Service
                              │
                              │ POST /api/v1/employees
                              │ (validates departmentId exists)
                              ▼
                       OpenFeign call → Department Service
                       "does department 42 exist?" → 200 OK
                              │
                       Employee Service saves employee, returns 201
```

Asynchronous path (side effects that don't need to block the response):
```
Employee Service
   │  after commit: publish "EmployeeCreated" event → Kafka topic
   ▼
   ┌─────────────────────────────┬──────────────────────────────┐
   ▼                             ▼                               ▼
Department Service        Notification Service              Audit Service
(increments local          (sends welcome email)             (writes audit log)
 headcount read-model)
```

This is the essence of **Event-Driven Architecture**: the Employee Service
does not need to know that Notification or Audit exist. It just announces
a fact ("an employee was created") and interested services react. This is
how you add new features (e.g. a future "Payroll Service") without ever
touching Employee Service's code.

We use **synchronous REST/Feign** when the caller needs an answer right now
to complete its own request (existence checks, validation). We use
**asynchronous messaging** for anything that is a side effect, can tolerate
a few seconds of delay, or fans out to multiple consumers.

---

## 6. Database Design (high level — full schemas arrive with each service)

**auth_db** (Auth Service)
- `users` (id, email, password_hash, enabled, email_verified, ...)
- `roles`, `user_roles` (RBAC)
- `refresh_tokens`
- `otp_codes`

**employee_db** (Employee Service)
- `employees` (id, first_name, last_name, email, department_id, status, ...)
- `employee_documents` (id, employee_id FK → employees, doc_type, storage_url)

**department_db** (Department Service)
- `departments` (id, name, parent_department_id, manager_employee_id, ...)
- `department_stats` (read-model table, kept in sync via Kafka events from
  Employee Service — this is *not* a foreign key into employee_db)

Note `manager_employee_id` and `department_id` are **not** SQL foreign keys
across databases — they are just IDs. Referential integrity across services
is enforced by application logic and events, not by the database. This is
one of the biggest mental shifts coming from monolith development.

---

## 7. Implementation (this step)

What actually exists in the repository after this step:

- Root `pom.xml` — Maven parent for dependency/version management. No
  modules yet; each is added to `<modules>` in the step that creates it.
- `COURSE-ROADMAP.md` — the running checklist for the whole course.
- `docs/architecture/01-project-architecture.md` — this document.
- Empty directories for each planned service (`discovery-server/`,
  `config-server/`, `api-gateway/`, `auth-service/`, `employee-service/`,
  `department-service/`, `notification-service/`), each holding a short
  `README.md` stating which course step will fill it in.
- `infra/docker/docker-compose.yml` — skeleton for local infrastructure
  (MySQL instances, Redis, Kafka, Zookeeper, Zipkin), extended as each
  service is added.

No business code is written yet — that starts in Step 2 with the Discovery
Server, deliberately built *before* any business service, because every
later service needs something to register with.

---

## 8. Testing

Nothing to test yet — there is no runnable code in this step. Starting
Step 2, every step ends with unit tests (JUnit + Mockito) and, once a
database is involved, TestContainers-based integration tests.

---

## 9. Interview Questions

1. **What is the difference between a monolith and microservices, and when
   would you NOT choose microservices?**
   *Look for:* team size, deployment independence, operational overhead
   awareness — not "microservices are always better."

2. **Why is "database per service" considered a core microservices
   principle, and what problem does a shared database actually cause?**
   *Look for:* coupling at the data layer defeats independent deployability
   — the "distributed monolith" anti-pattern.

3. **How do you decide service boundaries?**
   *Look for:* bounded contexts / DDD, team ownership (Conway's Law), not
   "one microservice per database table" (a common junior mistake).

4. **If Department Service needs employee counts, why not just JOIN across
   databases?**
   *Look for:* cross-database joins aren't physically possible in general
   (different DB instances) and, more importantly, decouple deploy
   cadences — the real answer is Feign call or an eventually-consistent
   local read model built from events.

5. **What is Conway's Law and how does it relate to microservices?**
   *Look for:* "Organizations design systems that mirror their own
   communication structure." Team topology should match service
   boundaries, or friction is guaranteed.

---

## 10. Best Practices

- Decompose by **business capability / bounded context**, not by technical
  layer (never "UI service", "database service").
- Design the **API contract first** between services before writing
  implementation — treat it like a public interface.
- Keep the number of initial services small. It is much easier to split a
  service later than to merge two badly-split services.
- Write down *why* a service exists — that "why" is what tells you what
  does **not** belong in it.

## 11. Common Mistakes

- Splitting services by technical layer (e.g. a "controller service" and a
  "database service") — this creates a distributed monolith with all the
  network overhead and none of the independence.
- Sharing one database "just for now" — it never gets fixed later; the
  coupling becomes permanent.
- Starting with 15 microservices for a system three people will maintain.
  Start with the fewest services that reflect real bounded contexts, and
  split further only when a real scaling or team-ownership pain shows up.
- Treating the Gateway as a place for business logic — it should only
  route, authenticate, and apply cross-cutting policies.

---

## 12. Summary

We now have: a clear picture of the whole system, a justified set of
service boundaries, a communication model (sync for request/response,
async for side effects and fan-out), a database-per-service policy, and a
repository skeleton the rest of the course fills in one module at a time.

**Next step (Step 2):** Discovery Server — why services need to find each
other dynamically, and how Eureka solves it, before we write any business
service.
