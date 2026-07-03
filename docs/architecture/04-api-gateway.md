# Step 4 — API Gateway (Spring Cloud Gateway)

## 1. Goal

Give clients exactly one address to talk to, and move cross-cutting
concerns — routing, authentication at the edge, rate limiting, CORS — out
of every individual service and into a single place. Build it now, with
both Discovery Server and Config Server already up, since the Gateway
needs both: discovery to find `EMPLOYEE-SERVICE`/`DEPARTMENT-SERVICE`/
`AUTH-SERVICE` by name, and Config Server to get its routes, JWT policy,
CORS origins, and rate limits.

Concretely, in this step we:
- Route requests to downstream services by Eureka application name
  (`lb://EMPLOYEE-SERVICE`), config-driven, not hardcoded in Java.
- Verify JWTs at the edge before a request is ever proxied anywhere — a
  lightweight check (signature + expiry), not full authorization.
- Rate-limit per client IP, in-memory for now, with a documented upgrade
  path to Redis in Step 10.
- Apply a single, explicit CORS policy for the whole platform.

## 2. Architecture Diagram

```
                              Client (browser / mobile / API consumer)
                                             │
                                             │ HTTPS, one address, one port
                                             ▼
                    ┌───────────────────────────────────────────────────┐
                    │                     API GATEWAY                      │
                    │                                                      │
                    │  [1] RateLimit filter (Ordered.HIGHEST_PRECEDENCE)   │
                    │        per-client-IP, in-memory Resilience4j          │
                    │        limiter → 429 if exceeded, chain never runs    │
                    │                       │                                │
                    │  [2] JwtAuthentication filter (order -1)               │
                    │        public path? → pass through                     │
                    │        else: verify Bearer token → 401 or continue      │
                    │                       │                                  │
                    │  [3] CORS filter (CorsWebFilter, path "/**")              │
                    │                       │                                   │
                    │  [4] Route matching (Path predicate) → lb://SERVICE-NAME   │
                    └───────────────────────┬───────────────────────────────────┘
                                             │ resolves via Eureka, client-side LB
              ┌──────────────────────────────┼───────────────────────────────┐
              ▼                              ▼                                ▼
     AUTH-SERVICE                   EMPLOYEE-SERVICE                 DEPARTMENT-SERVICE
     /api/v1/auth/**                /api/v1/employees/**              /api/v1/departments/**
```

Note what does **not** go through this pipeline: the Gateway's own
`/actuator/**` endpoints. Spring Cloud Gateway's `GlobalFilter`s only run
for requests that match a configured **route** — actuator endpoints are
served by a separate WebFlux handler mapping entirely. This trips people
up constantly (see Common Mistakes).

## 3. Folder Structure

```
api-gateway/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/gateway/
    │   │   ├── GatewayApplication.java
    │   │   ├── config/
    │   │   │   ├── CorsProperties.java
    │   │   │   └── CorsConfig.java            # CorsWebFilter bean
    │   │   ├── security/
    │   │   │   ├── JwtProperties.java
    │   │   │   ├── JwtValidator.java          # signature + expiry check (jjwt)
    │   │   │   └── JwtAuthenticationGlobalFilter.java
    │   │   └── ratelimit/
    │   │       ├── RateLimitProperties.java
    │   │       └── InMemoryRateLimiterGlobalFilter.java
    │   └── resources/
    │       └── application.yml    # bootstrap only: how to reach Config Server + Eureka
    └── test/
        ├── java/com/enterprise/ems/gateway/
        │   ├── GatewayApplicationTests.java          # full-stack proof
        │   ├── security/JwtValidatorTest.java         # pure unit
        │   ├── security/JwtAuthenticationGlobalFilterTest.java
        │   └── ratelimit/InMemoryRateLimiterGlobalFilterTest.java
        └── resources/application-test.yml
```

Business configuration — routes, JWT public paths, CORS origins, rate
limits — lives in `config-server/src/main/resources/config-repo/api-gateway.yml`,
not in this module. That split is deliberate and was established in Step 3.

## 4. Explanation

### Why a gateway at all

Without one, every client needs to know the address of every service, CORS
has to be configured N times identically, and there's no single place to
enforce "every request must carry a valid token." **Real-world analogy:**
a hotel's front desk is the one place guests interact with; behind it,
housekeeping, room service, and maintenance are separate teams the guest
never talks to directly, and the front desk enforces "show your room key"
uniformly regardless of which department eventually handles the request.

### Config-driven routing, not code

Routes live in YAML (`Path=/api/v1/employees/** → lb://EMPLOYEE-SERVICE`),
served by the Config Server, not hardcoded as Java `RouteLocator` beans.
Adding a new downstream service is then a config change, not a redeploy of
the Gateway — consistent with everything Step 3 established about not
duplicating knowledge across services.

### `lb://` — client-side load balancing all the way through

The `lb://` scheme tells Spring Cloud Gateway to resolve `EMPLOYEE-SERVICE`
via the `ReactiveLoadBalancer` (backed by Eureka, from Step 2) rather than
treating it as a literal hostname. If three instances of Employee Service
are registered, the Gateway picks one per request (round-robin by
default) — the same client-side load-balancing model used everywhere else
in this platform, just happening one hop earlier.

### JWT verification at the edge vs. authorization downstream

The Gateway checks exactly two things: **is this signature valid** (was
this token issued by our Auth Service, using the shared secret from Config
Server) and **has it expired**. It does **not** decide whether user
`emp-1234` is allowed to `DELETE /api/v1/employees/99` — that's Employee
Service's job in Step 6, using the `X-Auth-User-Id`/`X-Auth-Roles` headers
the Gateway attaches after a successful verification.

Why split it this way instead of doing full RBAC at the edge?
- The Gateway would need to know every service's authorization rules,
  recoupling all the services it's supposed to decouple.
- Downstream services are closer to the data and can enforce
  resource-level rules the Gateway can't see (e.g. "managers can only
  edit employees in their own department").
- A malformed/expired token is a platform-wide concern (reject fast,
  uniformly); "can this specific user do this specific thing" is a
  business rule that differs per service.

The `X-Auth-*` headers are trusted by downstream services **only** because
network policy (enforced in Step 20 with Kubernetes `NetworkPolicy`) makes
services unreachable except through the Gateway — otherwise anyone could
set those headers themselves and impersonate any user. This trust boundary
is a common interview topic and a common real-world misconfiguration.

### Rate limiting: why in-memory now, and its real limitation

Spring Cloud Gateway's built-in `RequestRateLimiter` filter factory ships
with a `RedisRateLimiter` implementation out of the box — but Redis isn't
introduced until Step 10. Rather than pull in Redis prematurely, we
implement a small `GlobalFilter` backed by Resilience4j's reactive
`RateLimiter`, keyed per client IP, with `timeoutDuration(Duration.ZERO)`
(fail immediately with 429 rather than queue).

The honest limitation: this is **per gateway instance**, not cluster-wide.
Run 3 Gateway replicas and a client can get up to 3× the configured limit
by chance of load balancing. It also never evicts old per-IP limiter
entries, so a very large number of distinct clients grows memory over the
process's lifetime. Step 10 swaps the in-memory registry for a
Redis-backed limiter, which fixes both problems at once (shared state
across instances, and TTL-based eviction) — the filter's external
behavior (429 on rejection) does not change, only its backing store.

### Ordering: rate limit before JWT verification

`InMemoryRateLimiterGlobalFilter` runs at `Ordered.HIGHEST_PRECEDENCE`,
before `JwtAuthenticationGlobalFilter` (order `-1`). This is deliberate: a
flood of requests with garbage tokens should be shed before spending CPU
on cryptographic signature verification for each one. Rate limiting is
the cheapest possible check and should always run first.

### CORS: an explicit origin allow-list, not a wildcard

`CorsConfiguration.setAllowCredentials(true)` is required so browsers will
send the `Authorization` header cross-origin — but the CORS specification
forbids combining a wildcard origin (`*`) with credentialed requests, and
Spring enforces this. `gateway.cors.allowed-origins` must therefore always
be an explicit list of real origins, sourced from Config Server so it can
differ safely per environment (`localhost:3000` for local dev, the real
frontend domain in production) without a code change.

## 5. Implementation

Module: `api-gateway/`, added to the root `pom.xml` `<modules>`. New root
POM dependency management: `jjwt-bom` (0.12.7) for JWT parsing/validation,
`resilience4j-bom` (2.2.0) for the in-memory rate limiter.

- **`JwtProperties`** — record binding `security.jwt.secret` (no local
  default — must come from Config Server) and `security.jwt.public-paths`.
- **`JwtValidator`** — wraps `io.jsonwebtoken` (jjwt): builds an HMAC
  `SecretKey` from the shared secret, parses/verifies a token, throwing
  `JwtException` (or a subtype) on any problem (expired, bad signature,
  malformed).
- **`JwtAuthenticationGlobalFilter`** — `GlobalFilter` at order `-1`.
  Ant-pattern-matches the request path against `publicPaths`; if not
  public, requires `Authorization: Bearer <token>`, validates it, and on
  success mutates the request to add `X-Auth-User-Id`/`X-Auth-Roles`
  headers before continuing the chain. On failure, short-circuits with a
  JSON 401 body — the downstream chain (and any real network call to a
  backend) never runs.
- **`RateLimitProperties`** / **`InMemoryRateLimiterGlobalFilter`** —
  `GlobalFilter` at `Ordered.HIGHEST_PRECEDENCE`. Builds one
  Resilience4j `RateLimiter` per client IP via a `RateLimiterRegistry`,
  wraps the downstream `chain.filter(exchange)` with
  `RateLimiterOperator`, and maps a rejected acquisition
  (`RequestNotPermitted`) to an HTTP 429 JSON response.
- **`CorsProperties`** / **`CorsConfig`** — binds
  `gateway.cors.allowed-origins/methods/headers` and registers a
  `CorsWebFilter` for `/**`.
- **`application.yml`** (bootstrap only) — `spring.config.import=optional:configserver:...`
  so a Config Server outage doesn't crash gateway startup (it logs a
  warning and falls back to whatever's defined locally — none of which is
  safety-critical here), plus the Eureka client URL.
- **`config-repo/api-gateway.yml`** (Config Server, Step 3) — the actual
  routes, JWT public paths, CORS origins/methods, and rate-limit numbers.
- **`Dockerfile`** — same multi-stage pattern as prior services.
- **`infra/docker/docker-compose.yml`** — `api-gateway` now depends on
  `config-server` being healthy.

## 6. Testing

Four test classes, 14 tests total, all passing:

- **`JwtValidatorTest`** (pure unit, no Spring context) — valid token
  round-trips its claims; an expired token, a token signed with a
  different key, and a structurally malformed string all throw
  `JwtException`.
- **`JwtAuthenticationGlobalFilterTest`** (unit, `MockServerWebExchange` +
  a mocked `GatewayFilterChain`) — public paths pass through without a
  token; protected paths reject a missing token and a garbage token with
  401 *without ever invoking the chain*; a valid token passes through and
  the downstream chain is invoked exactly once.
- **`InMemoryRateLimiterGlobalFilterTest`** (unit) — the 4th and 5th
  request from the same simulated client IP within the refresh window get
  429 while the chain is invoked only 3 times (the configured limit); two
  different client IPs are rate-limited independently. **Caught one real
  bug while writing this test**: the first version's Mockito stub
  incremented its call counter *eagerly* (`thenAnswer` running
  synchronously) rather than on subscription, which silently defeated the
  whole point of testing a reactive, lazily-subscribed pipeline — every
  call "succeeded" regardless of what the rate limiter decided. Fixed by
  wrapping the counter increment in `Mono.defer(...)`, matching how a real
  `GatewayFilterChain` behaves (nothing happens until subscribed).
- **`GatewayApplicationTests`** (full Spring context, random port,
  `WebTestClient`) — proves the filter is wired into the *live* pipeline,
  not just unit-tested in isolation: a protected route with no token or a
  garbage token gets 401 before any routing is attempted; a protected
  route with a valid token, and a public auth route with no token, both
  get *something other than 401* (there's no real backend running in this
  test, so they legitimately fail at the routing/load-balancer step — the
  assertion is specifically "the JWT filter did not block this," not
  "the full request succeeded end to end").

```
mvn -pl api-gateway -am verify
```

## 7. Interview Questions

1. **Why terminate authentication at the API Gateway instead of in every
   downstream service?**
   *Look for:* single enforcement point, reduced duplication — balanced
   against the trade-off that the Gateway becomes a single point that,
   if buggy, could let unauthorized/expired tokens through everywhere at
   once. Emphasize the split: cheap validation at the edge, real
   authorization decisions stay downstream.

2. **How does `lb://SERVICE-NAME` routing actually resolve to an
   instance?**
   *Look for:* Eureka lookup + client-side load balancing (Spring Cloud
   LoadBalancer), not a hardcoded address; ties directly back to Step 2.

3. **Why is trusting `X-Auth-User-Id` from a header dangerous unless a
   specific condition holds — what is that condition?**
   *Look for:* it's only safe if the network topology makes it impossible
   for a client to reach the downstream service directly (bypassing the
   Gateway) — otherwise anyone can set that header and impersonate any
   user. This is exactly what Kubernetes `NetworkPolicy` (Step 20)
   enforces.

4. **What's wrong with rate-limiting per gateway instance instead of
   cluster-wide, and how would you fix it?**
   *Look for:* a client can get N× the intended limit across N replicas;
   fix is a shared, external store (Redis) all instances check against.

5. **Why does Spring reject `Access-Control-Allow-Origin: *` combined with
   credentialed requests, and what does that force you to do
   operationally?**
   *Look for:* the CORS spec explicitly forbids it as a security measure
   (a wildcard + cookies/auth headers would let any site read
   authenticated responses); forces maintaining an explicit,
   environment-specific origin allow-list instead of a shortcut wildcard.

## 8. Best Practices

- Keep the Gateway's own logic to routing and cross-cutting concerns only
  — the moment it starts containing business rules, you've built a
  second, hidden application layer nobody looks at.
- Order `GlobalFilter`s deliberately and document why (cheapest checks
  first).
- Fail closed on missing/invalid auth (401 by default), fail loud on
  config problems you can't safely default around, and fail soft
  (`optional:` import + sane fallback) only where the missing config truly
  isn't safety-critical.
- Treat the Gateway as the most security-critical piece of the platform —
  every request a real user makes passes through it.

## 9. Common Mistakes

- Assuming `/actuator/**` requests pass through Gateway `GlobalFilter`s —
  they don't; they're served by a separate handler mapping entirely, so
  "protecting" actuator via a Gateway filter's path whitelist does
  nothing (and omitting it from the whitelist does nothing either).
- Doing full authorization logic at the Gateway "since it's already
  checking the token anyway," which quietly re-centralizes business rules
  that belong in each service.
- Setting `allowedOrigins("*")` together with `allowCredentials(true)` and
  being surprised when Spring rejects the combination at runtime.
- Forgetting that a Mockito stub with `thenAnswer` running eager side
  effects doesn't correctly simulate a lazily-subscribed reactive
  pipeline — exactly the bug this step's own rate-limiter test hit and
  had to fix.
- Sizing an in-memory rate limiter's window carelessly without
  acknowledging (and documenting) that it isn't cluster-wide.

## 10. Summary

Every client request now flows through exactly one door: rate-limited,
authenticated at the edge, CORS-controlled, and routed by service name
resolved live from Eureka. Business services (still to be built) will
trust `X-Auth-User-Id`/`X-Auth-Roles` headers and focus purely on
authorization and business logic, not token parsing.

**Next step (Step 5): Authentication Service** — the service that will
actually issue the JWTs this Gateway has been verifying against a shared
secret all along, plus registration, refresh tokens, RBAC, OAuth2 Google
login, and OTP/email verification.
