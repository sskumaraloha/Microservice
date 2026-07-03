# Step 2 — Discovery Server (Netflix Eureka)

## 1. Goal

Give every microservice a way to find every other microservice **by name**
at runtime, without hardcoding IP addresses or ports anywhere. Build this
before any business service, because Employee, Department, Auth, and the
Gateway will all depend on it from the moment they exist.

Concretely, in this step we:
- Stand up a Eureka Server as its own Spring Boot application.
- Secure its dashboard and REST API with HTTP Basic auth (an unsecured
  registry leaks your entire internal service topology).
- Keep `/actuator/health` open, unauthenticated, for container orchestrator
  probes.
- Configure it for both **standalone** (local dev) and **peer-aware HA**
  (production, two nodes replicating to each other) modes from one codebase.

## 2. Architecture Diagram

```
                         ┌─────────────────────────────┐
                         │        Discovery Server        │
                         │        (Eureka Registry)        │
                         │                                  │
                         │  In-memory registry:              │
                         │   EMPLOYEE-SERVICE  -> [ip:port,…] │
                         │   DEPARTMENT-SERVICE-> [ip:port,…] │
                         │   AUTH-SERVICE      -> [ip:port,…] │
                         │   API-GATEWAY       -> [ip:port,…] │
                         └───────────┬─────────────────────┘
                                     │   1. register + heartbeat every 10s
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                       ▼
     Employee Service         Department Service        Auth Service
     (Eureka Client)          (Eureka Client)            (Eureka Client)
              │                      ▲
              │  2. "where is        │
              │     DEPARTMENT-      │
              │     SERVICE?"        │
              └──────────────────────┘
              3. registry answers with a live instance list,
                 client picks one (client-side load balancing)
```

Production HA topology (Step 20 deploys this on Kubernetes):

```
   ┌───────────┐   replicates registry    ┌───────────┐
   │  peer1     │◄─────────────────────────►│  peer2     │
   │ (Eureka)   │                            │ (Eureka)   │
   └─────┬──────┘                            └─────┬──────┘
         │  clients register with BOTH urls, prefer whichever answers │
         └───────────────────────┬───────────────────────────────────┘
                                  ▼
                         any business service
```

## 3. Folder Structure

```
discovery-server/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/discovery/
    │   │   ├── DiscoveryServerApplication.java   # @EnableEurekaServer
    │   │   └── config/
    │   │       └── EurekaServerSecurityConfig.java
    │   └── resources/
    │       └── application.yml    # default (standalone) + peer1 + peer2 profiles
    └── test/
        ├── java/com/enterprise/ems/discovery/
        │   └── DiscoveryServerApplicationTests.java
        └── resources/
            └── application-test.yml
```

This is the smallest module in the whole platform — a discovery server
has no domain logic, no database, no DTOs. Its only job is the registry
itself plus the security wrapped around it.

## 4. Explanation

### The problem this solves

Without service discovery, if Employee Service wants to call Department
Service, it needs Department Service's address hardcoded or in config:
`department-service.internal:8082`. That breaks the moment you:
- scale Department Service to 3 replicas (which address do you hardcode?),
- move it to a different host,
- run it in Kubernetes where pod IPs are ephemeral by design.

**Real-world analogy:** think of Eureka as a company's internal phone
directory that updates itself continuously. New employee joins → they
"check in" and their extension is listed immediately. Employee leaves or
stops answering → they're removed automatically after a few missed
check-ins. Nobody keeps a printed phone list that goes stale; everybody
just asks the directory.

### How registration and discovery actually happen

1. **Registration**: on startup, each service (a Eureka *client*) sends a
   `POST /eureka/apps/{APP-NAME}` to the registry with its host, port, and
   health status.
2. **Heartbeat (renewal)**: every 10 seconds (`lease-renewal-interval-in-seconds`)
   the client sends a heartbeat. If the registry doesn't hear from an
   instance for 30 seconds (`lease-expiration-duration-in-seconds`), the
   instance is considered down and is eventually evicted.
3. **Discovery**: a client asks the registry (or, more commonly, uses a
   locally cached copy refreshed every 30s) "give me all instances of
   `DEPARTMENT-SERVICE`" and gets back a list of `ip:port` pairs.
4. **Client-side load balancing**: the calling service (via Spring Cloud
   LoadBalancer, wired automatically once OpenFeign/`@LoadBalanced`
   `RestTemplate`/`WebClient` are added in Step 8) picks one instance from
   that list itself — there is no separate load balancer process in the
   hot path. This is why Eureka clients cache the registry locally: so a
   registry outage does not stop existing instances from calling each
   other with the last-known-good list.

### Self-preservation mode — the most misunderstood Eureka setting

If more than 15% of instances (`renewal-percent-threshold: 0.85`) stop
sending heartbeats within a short window, Eureka assumes **the network
itself is unhealthy** (a partition), not that every instance actually
died, and stops evicting anyone. The trade-off: during a real network
blip, you might call an instance that's actually down (get a connection
error, and Resilience4j/retry handles it in Step 8) — versus, without
self-preservation, a network blip could evict your *entire* fleet from
the registry simultaneously, and now nobody can discover anybody. In
production, self-preservation should always stay on; only disable it for
single-node local development where you'd rather see instant, accurate
eviction while debugging.

### Why we secure the registry

The Eureka dashboard (`GET /`) and the raw REST API (`GET /eureka/apps`)
list every registered service, every instance's IP:port, and metadata.
That is a **reconnaissance map of your internal network** if exposed. We
require HTTP Basic auth for everything except `/actuator/health` and
`/actuator/info`, which Kubernetes liveness/readiness probes must reach
without credentials. CSRF protection is disabled specifically under
`/eureka/**` because Eureka clients register via plain POST/PUT/DELETE and
have no way to fetch a CSRF token first — this is the standard, documented
trade-off for this endpoint family, not a blanket CSRF disable.

### Standalone vs peer-aware (HA)

A single Eureka node is a single point of failure: if it goes down, no
*new* instance can register and no client can refresh its registry cache
(existing clients keep working from their last cached copy, which is why
Eureka is often called "available over consistent" — see the CAP theorem
interview question below). The `peer1`/`peer2` Spring profiles in
`application.yml` turn on `register-with-eureka` and `fetch-registry` and
point each node at the other, so both replicate the same registry data —
a client can query either node and see the same picture, and losing one
node doesn't blind the whole platform.

## 5. Implementation

Module: `discovery-server/`, added to the root `pom.xml` `<modules>`.

- **`DiscoveryServerApplication`** — a single `@EnableEurekaServer` on top
  of `@SpringBootApplication`. That annotation is what turns a plain Spring
  Boot app into a full Eureka registry (registration endpoints, the
  dashboard, replication logic if peer-aware).
- **`EurekaServerSecurityConfig`** — a `SecurityFilterChain` that permits
  `/actuator/health/**` and `/actuator/info` unauthenticated, requires
  HTTP Basic for everything else, and disables CSRF only for `/eureka/**`.
- **`application.yml`** — three blocks:
  - default: standalone, `register-with-eureka: false`, `fetch-registry: false`
    (a lone registry doesn't need to be its own client).
  - `peer1` / `peer2`: HA profiles, each registering with the other.
  - Credentials come from `EUREKA_SERVER_USERNAME` / `EUREKA_SERVER_PASSWORD`
    environment variables with local-only defaults — Step 3 moves this to
    Config Server, Step 20 moves the real values into a Kubernetes Secret.
- **`Dockerfile`** — multi-stage build. Build context is the **repository
  root** (not `discovery-server/`), because this is a Maven multi-module
  reactor and the build needs the parent POM to resolve dependency
  versions:
  ```
  docker build -f discovery-server/Dockerfile -t ems/discovery-server:latest .
  ```
  The runtime stage uses a non-root user and a `HEALTHCHECK` that hits
  `/actuator/health`.
- **`infra/docker/docker-compose.yml`** — now runs `discovery-server` so
  `docker compose -f infra/docker/docker-compose.yml up --build` gives you
  a working registry at `http://localhost:8761`.

Run it locally without Docker:
```
mvn -pl discovery-server -am spring-boot:run
```
Then open `http://localhost:8761` and log in with
`eureka-admin` / `change-me-in-production` (override both via env vars —
never ship that default).

## 6. Testing

`DiscoveryServerApplicationTests` (Spring Boot Test, random port, `test`
profile) verifies the two things that actually matter at this layer:

1. **Context loads** — catches any misconfiguration in the Eureka/security
   auto-configuration wiring immediately.
2. **`/actuator/health` is reachable without credentials** — proves
   Kubernetes-style probes will work.
3. **`/` (dashboard) rejects unauthenticated requests with 401.**
4. **`/` accepts valid Basic auth credentials with 200.**

```
mvn -pl discovery-server -am verify
```
All 4 tests pass. There is intentionally no test for "does Eureka
correctly replicate a registration" here — that is Netflix's own
well-tested library code, not something we wrote; testing it would be
testing the framework, not our integration of it.

## 7. Interview Questions

1. **Why do services need service discovery instead of a static config
   file of addresses?**
   *Look for:* ephemeral IPs in containers/Kubernetes, dynamic scaling,
   the operational cost of manually updating address lists.

2. **Explain Eureka's self-preservation mode. What problem does it solve
   and what does it trade away?**
   *Look for:* distinguishing "many instances actually died" from "the
   network is partitioned"; trades perfectly-accurate-registry for
   not-mass-evicting-healthy-instances.

3. **Is Eureka CP or AP in CAP theorem terms, and why does that matter?**
   *Look for:* Eureka is AP — it favors **availability** or (partial
   response) over strict consistency: clients keep working off a cached
   registry during a partition rather than refusing to serve because they
   can't confirm the "true" state. Contrast with ZooKeeper/etcd (CP),
   which is why Kubernetes' own service discovery (built on etcd) behaves
   differently under partition.

4. **What's the difference between client-side and server-side load
   balancing, and which does Eureka + Spring Cloud LoadBalancer use?**
   *Look for:* client-side — the calling service holds the instance list
   and picks one itself, vs. a server-side LB (like an ALB or nginx) that
   sits in the request path for every call.

5. **Why secure the Eureka dashboard, and why is CSRF disabled for
   `/eureka/**` specifically instead of globally?**
   *Look for:* information disclosure of internal topology; CSRF tokens
   require a prior GET to fetch the token, which service-to-service
   registration calls never do — disabling it narrowly (not globally)
   keeps CSRF protection for any human-facing endpoints that might exist.

## 8. Best Practices

- Never expose the Eureka dashboard/API without authentication, even
  "internally" — internal networks get breached too (defense in depth).
- Always keep self-preservation on in any multi-instance environment.
- Run at least two peer-aware nodes in production; one node is a SPOF.
- Keep heartbeat/eviction intervals as defaults unless you have measured a
  specific reason to change them — overly aggressive eviction causes
  false-positive "instance down" flapping.
- Let container orchestrator health checks hit `/actuator/health`
  specifically, never the dashboard or an authenticated endpoint.

## 9. Common Mistakes

- Leaving the dashboard/API publicly reachable "just for now."
- Turning off self-preservation in production to get a "more accurate"
  registry — this is backwards; it trades safety for an illusion of
  precision and can cause mass eviction storms during minor network hiccups.
- Hardcoding a single Eureka URL in every client with no fallback — if
  that one node restarts during a deploy, every client briefly can't
  refresh its cache. Peer-aware mode plus listing both URLs in
  `service-url.defaultZone` (comma-separated) avoids this.
- Treating Eureka as a source of strong consistency (e.g. using it to
  decide "is this the leader") — it is explicitly AP, not CP; that's the
  wrong tool for leader election.

## 10. Summary

We now have a secured, testable, containerized Eureka registry that can
run standalone for local development or as a two-node HA cluster in
production, using the exact same code and just a different Spring
profile. Every service built from here forward will add one dependency
(`spring-cloud-starter-netflix-eureka-client`) and a few lines of config
to register here — no service will ever need another service's IP
address hardcoded.

**Next step (Step 3): Config Server** — before writing the first business
service, centralize configuration so `auth_db`, `employee_db`, and
`department_db` connection details, feature flags, and per-environment
settings live in one versioned place instead of being duplicated across
every service's `application.yml`.
