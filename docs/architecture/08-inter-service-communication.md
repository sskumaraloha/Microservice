# Step 8 — Inter-Service Communication (OpenFeign, WebClient, Resilience4j)

## 1. Goal

Make two services call each other over real HTTP for the first time, and
do it safely:

- **Employee Service → Department Service.** Before Employee Service
  writes an employee, it must know the given `departmentId` actually
  exists — a rule the database's own foreign key constraint can't enforce
  here, because [Step 1](./01-project-architecture.md) forbids
  cross-service foreign keys (database-per-service). This is implemented
  with **OpenFeign**, resolved through Eureka exactly like the Gateway's
  routes (Step 4).
- **Department Service → Employee Service.** `GET /departments/{id}/statistics`
  (Step 7) now reports a live employee headcount, closing the gap that
  step's lesson deliberately left open. This is implemented with
  **WebClient**, addressed by a plain, config-provided URL instead of an
  Eureka service name — the two clients are built differently on purpose,
  so this step teaches both common patterns for the same underlying
  problem side by side.

Both calls are wrapped in **Resilience4j**: Timeout, Retry, Circuit
Breaker, Bulkhead, and Fallback. Rate Limiter is *not* duplicated here —
it already exists at the API Gateway (Step 4), protecting every downstream
service from the client side; adding a second one on an internal
service-to-service call would duplicate that protection without a
distinct purpose. Each call also gets a genuinely different fallback
philosophy, chosen for what the data actually means:

| | Employee → Department | Department → Employee |
|---|---|---|
| What's being checked | "Does this department exist?" — a write-time business rule | "How many employees does this department have?" — supplementary statistics |
| If the dependency is down | **Fail loudly.** Creating an employee against an unverifiable department is unsafe to allow → `503 Service Unavailable` | **Degrade gracefully.** A dashboard shouldn't fail entirely because one number couldn't be computed → `employeeHeadcount: null`, `200 OK` |

## 2. Architecture Diagram

```
                         ┌─────────────────────┐
        POST /employees  │   Employee Service    │  GET /employees/count?departmentId=
      ───────────────────▶                        ◀───────────────────────────────────┐
                         │  DepartmentValidation-  │                                    │
                         │  ServiceImpl            │                                    │
                         │   @CircuitBreaker        │                                    │
                         │   @Retry (fallback here)  │                                    │
                         │   @Bulkhead                │                                    │
                         │        │                    │                                    │
                         │   DepartmentClient           │                                    │
                         │   (OpenFeign, name=            │                                    │
                         │    "department-service")         │                              WebClient
                         └────────┼──────────────────────┘                        (plain baseUrl, not
                                  │ resolved via Eureka                            Eureka-resolved)
                                  ▼                                                        │
                         ┌─────────────────────┐                                          │
                         │  Department Service   │◀─────────────────────────────────────────┘
                         │                        │
                         │  DepartmentController   │
                         │  GET /departments/{id}    │      ┌─────────────────────┐
                         │                             │    │ EmployeeHeadcount-    │
                         │  DepartmentServiceImpl        │──▶│ ClientImpl             │
                         │  .getStatistics() now calls     │  @CircuitBreaker         │
                         │  EmployeeHeadcountClient           │ @Retry (fallback here)   │
                         └─────────────────────────────────┘  @Bulkhead                 │
                                                                (returns null on           │
                                                                 exhaustion, never throws)  │
                                                               └──────────────────────────┘

Resilience4j decoration order (outer → inner) on BOTH clients:

   Retry ( CircuitBreaker ( Bulkhead ( real HTTP call ) ) )

fallbackMethod lives on @Retry, not the inner @CircuitBreaker, on both
clients — see section 4, "The fallback-placement gotcha," for why that
placement is load-bearing, not stylistic.
```

## 3. Folder Structure

```
employee-service/
└── src/main/java/com/enterprise/ems/employee/
    ├── client/
    │   ├── DepartmentClient.java          # @FeignClient, Eureka-resolved
    │   └── DepartmentSummary.java         # minimal response record
    ├── config/
    │   └── FeignGlobalConfig.java         # Retryer.NEVER_RETRY + auth header propagation
    ├── service/
    │   ├── DepartmentValidationService.java
    │   └── impl/DepartmentValidationServiceImpl.java   # the 4 resilience annotations
    └── exception/
        ├── InvalidDepartmentException.java             # 400 — department doesn't exist
        └── DepartmentServiceUnavailableException.java  # 503 — couldn't verify

department-service/
└── src/main/java/com/enterprise/ems/department/
    ├── client/
    │   ├── EmployeeHeadcountClient.java       # interface, nullable-returning
    │   └── EmployeeHeadcountClientImpl.java   # WebClient + resilience annotations
    └── config/
        └── WebClientConfig.java               # plain-URL WebClient + auth header propagation

employee-service/src/test/java/.../client/
    └── DepartmentValidationServiceWireMockTest.java   # 4 tests, real HTTP against WireMock

department-service/src/test/java/.../client/
    └── EmployeeHeadcountClientWireMockTest.java        # 3 tests, real HTTP against WireMock
```

## 4. Explanation

### Why two different client-resolution strategies for the two directions

`DepartmentClient` is declared `@FeignClient(name = "department-service", ...)`
— Feign resolves `"department-service"` through Eureka and load-balances
across however many instances are registered, the same mechanism the
Gateway's `lb://DEPARTMENT-SERVICE` routes use (Step 4).

`EmployeeHeadcountClientImpl`'s `WebClient` is built from a plain
`employee-service.base-url` property instead. Both are legitimate,
common real-world patterns for the same problem, shown side by side
deliberately:

- **Feign + Eureka**: declarative, in-process client-side load balancing.
  The application decides which instance to call.
- **WebClient + a plain URL**: the platform (Docker Compose's embedded
  DNS today, a Kubernetes `Service`/service mesh sidecar tomorrow)
  already load-balances at the infrastructure layer, so the application
  doesn't need to do it again in-process.

Running both against the same dependency would be redundant, not
additive — picking one per integration, and understanding *why* that one
was picked, matters more than picking the "more advanced-looking" option
everywhere.

### The fallback-placement gotcha

A Resilience4j `fallbackMethod` intercepts **every** exception the
annotation it's attached to sees — not just the final, "I give up"
exception. Attach it to the wrong annotation and the fallback fires far
more often than intended:

```java
// WRONG: fires on every single failed attempt, before Retry ever
// gets a chance to retry.
@CircuitBreaker(name = "department-service", fallbackMethod = "fallback")
@Retry(name = "department-service")
public boolean departmentExists(Long departmentId) { ... }
```

```java
// RIGHT: Retry is the outermost aspect, so its fallback only fires
// once every attempt this call is willing to make has been exhausted.
@CircuitBreaker(name = "department-service")
@Retry(name = "department-service", fallbackMethod = "fallback")
public boolean departmentExists(Long departmentId) { ... }
```

Resilience4j's documented decoration order is
`Retry(CircuitBreaker(Bulkhead(call)))` — Retry outermost. Put
`fallbackMethod` on the inner `@CircuitBreaker` and it converts the raw
exception (or swallows it, if the fallback returns a value instead of
rethrowing) on **every** attempt — Retry, sitting outside, either sees a
wrapped/laundered exception it wasn't meant to see, or on
`EmployeeHeadcountClientImpl`'s side, sees no exception at all (because
the fallback already returned `null`) and stops after a single real
attempt, never retrying transient failures at all. Moving
`fallbackMethod` to `@Retry` fixes both problems: the raw exception
propagates normally through every real attempt, and the fallback runs
exactly once, with the *final* exception, after retries are genuinely
exhausted.

### The debugging story: a client-side retry hiding inside the test, not the code

While writing `DepartmentValidationServiceWireMockTest`'s "repeated
500s" scenario, WireMock reported **5** real requests instead of the
expected 3 (`resilience4j.retry.instances.department-service.max-attempts: 3`).
The investigation, in order:

1. Suspected Feign's own built-in retryer (default: up to 5 attempts)
   compounding with Resilience4j's `@Retry`. Fixed by registering
   `Retryer.NEVER_RETRY` in `FeignGlobalConfig` — **no change**, still 5.
2. Stripped `@Retry`/`@Bulkhead` down to just `@CircuitBreaker` — still
   **2** real requests for what should have been one logical call with
   zero retry annotations active at all.
3. Added a diagnostic log line directly inside
   `DepartmentValidationServiceImpl.departmentExists()` and, separately,
   inside `EmployeeController.create()`. The controller log revealed the
   real shape of the bug: the **entire HTTP POST** was arriving at the
   embedded Tomcat *twice*, roughly one second apart, on two different
   request-handling threads — not a retry inside the service at all.
4. A logged stack trace at the second controller invocation showed a
   completely fresh `NioEndpoint$SocketProcessor` → `CoyoteAdapter` chain
   — a genuine new socket accept, not an internal Tomcat/Spring async
   redispatch.
5. Enabling Apache HttpClient 5 wire-level debug logging on the test's
   own `TestRestTemplate` surfaced the actual cause:
   `HttpRequestRetryExec ... wait for 1 SECONDS`, immediately after the
   first `503` response was read.

**Root cause:** `spring-cloud-netflix-eureka-client` pulls in Apache
HttpClient 5, and `TestRestTemplate` auto-selects it once it's on the
classpath. HttpClient 5's `DefaultHttpRequestRetryStrategy` retries any
request whose response is `429` or `503` — regardless of HTTP method —
by default. That's a second, client-side retry loop stacked on top of
the server-side one this test exists to measure: the test client itself
resent the whole `POST /employees` after the server's (entirely correct)
first `503`, doubling the number of real Department Service calls the
test observed.

**Fix:** disable it for this one test, not in production code (the
production server correctly returns `503` once; nothing about the
service itself was wrong):

```java
@TestConfiguration
static class NoAutoRetryRestTemplateConfig {
    @Bean
    RestTemplateCustomizer disableHttpClientAutomaticRetries() {
        return restTemplate -> restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                        .build()));
    }
}
```

The lesson generalizes past this one test: when a test's *assertion
target* is "how many times did my server retry," always check what the
test's *own HTTP client* is doing first. A resilience test that isn't
itself resilience-neutral will lie about the very thing it's measuring.
This is also exactly why `EmployeeHeadcountClientWireMockTest` never hit
this problem at all — Department Service's headcount failures degrade to
`employeeHeadcount: null` with a `200 OK`, never a `503`, so the
retry-on-`503` behavior never had anything to trigger on. A design choice
made for one reason (graceful degradation) incidentally sidestepped an
entire class of test infrastructure bug.

### Why `@Configuration` was deliberately left off `FeignGlobalConfig`

`@EnableFeignClients(defaultConfiguration = FeignGlobalConfig.class)`
builds an **isolated child `ApplicationContext`** per Feign client from
the classes passed to it — that isolation is what keeps a Feign-specific
`Retryer` bean from leaking into the rest of the application. But
`FeignGlobalConfig` lives in `com.enterprise.ems.employee.config`, inside
the main application's own component-scanned package. Annotating it
`@Configuration` would register it a *second* time via ordinary component
scanning, defeating the isolation it exists to provide. Plain `@Bean`
methods still work without `@Configuration` (Spring processes such
classes in "lite" mode); the only capability lost is one `@Bean` method
calling another and getting back the same proxied singleton, which
neither bean here relies on.

## 5. Implementation

**Employee Service:**
- `DepartmentClient` — `@FeignClient(name = "department-service", url = "${department-service.base-url:}")`.
  `url` is blank in every real environment (Eureka resolves the name
  normally); it exists purely so tests can point the client at WireMock.
- `FeignGlobalConfig` — `Retryer.NEVER_RETRY` (Feign's own retry
  mechanism must never compound with Resilience4j's), and a
  `RequestInterceptor` that copies `X-Auth-User-Id`/`X-Auth-Roles` from
  the inbound request onto the outbound Feign call.
- `DepartmentValidationServiceImpl.departmentExists()` — catches
  `FeignException.NotFound` and returns `false` (a 404 is a valid
  business answer, never a failure to retry); any other exception is a
  real infrastructure failure and flows through `@CircuitBreaker` →
  `@Retry` → `@Bulkhead` → the `fallback` that throws
  `DepartmentServiceUnavailableException`.
- `EmployeeServiceImpl.create()`/`update()` call
  `requireValidDepartment()`, which throws `InvalidDepartmentException`
  (400) when the department genuinely doesn't exist — kept distinct from
  the 503 case (couldn't verify) throughout: different causes, different
  client-side remediation, different HTTP status.
- New `GET /employees/count` endpoint, filtered by the same
  `EmployeeSearchCriteria` used by search — this is what Department
  Service's headcount call actually hits.

**Department Service:**
- `WebClientConfig.employeeServiceWebClient()` — a plain `WebClient`
  built from `employee-service.base-url`, with explicit connect/read
  timeouts on the underlying `reactor-netty` `HttpClient` (WebClient has
  no automatic timeout of its own; leaving it unconfigured means
  "wait forever," which defeats the entire point of the Timeout concept
  this step teaches) and the same auth-header-propagation filter pattern
  as the Feign side, adapted to `ExchangeFilterFunction`.
- `EmployeeHeadcountClientImpl.getHeadcount()` — a **blocking** call
  (`.block()`) inside an otherwise synchronous, servlet-stack service.
  This is a deliberate, common real-world pattern: using `WebClient`
  instead of the now-in-maintenance-mode `RestTemplate` for a single
  outbound call does not require adopting the reactive stack everywhere.
  `.block()` is safe here because it runs on an ordinary Tomcat
  request-handling thread, never on a Reactor Netty event-loop thread.
- `DepartmentStatisticsResponse.employeeHeadcount` — nullable
  `Integer`. `null` means "Employee Service couldn't answer right now,"
  never "zero employees"; callers must not conflate the two.

## 6. Testing

**Employee Service** — `DepartmentValidationServiceWireMockTest` (4
tests, real HTTP against an embedded WireMock server standing in for
Department Service, nothing mocked at the Java level):
- an existing department allows employee creation and the auth headers
  are verifiably propagated onto the outbound Feign call;
- a missing department (404) is rejected with **exactly one** request —
  proof a legitimate "not found" answer is never retried;
- persistent infrastructure failures (500) are retried exactly
  `max-attempts` times, then surface as `503`;
- a transient failure that recovers inside the retry budget still
  succeeds, with exactly 2 requests observed.

**Department Service** — `EmployeeHeadcountClientWireMockTest` (3
tests, same real-HTTP-against-WireMock approach, going through the real
`GET /departments/{id}/statistics` endpoint rather than calling the
client bean directly, so the header-propagation filter — which reads the
inbound request via `RequestContextHolder`, only available inside an
active HTTP request — is actually exercised):
- a responding Employee Service reports a real headcount, with the auth
  headers propagated;
- persistent failures are retried the configured number of times, then
  the **whole statistics request still returns `200 OK`** with
  `employeeHeadcount: null` — proof the soft-fail design actually holds;
- a transient failure that recovers inside the retry budget still
  reports a headcount.

Both test classes force-reset their `CircuitBreakerRegistry` entry in
`@BeforeEach` (`transitionToForcedOpenState()` then
`transitionToClosedState()` — calling `transitionToClosedState()` alone
while already `CLOSED` is a no-op that leaves the sliding window's
recorded calls in place), because the breaker is a singleton shared
across every test method in the same Spring context.

```
mvn -pl employee-service,department-service -am test
```

40 tests pass in employee-service (1 self-skipped without Docker), 30 in
department-service (1 self-skipped without Docker).

## 7. Interview Questions

1. **Why does this system use OpenFeign for one direction of
   service-to-service calls and WebClient for the other, instead of
   picking one client and using it everywhere?**
   *Look for:* they represent two different, equally valid load-balancing
   philosophies — Feign + Eureka does in-process client-side load
   balancing; WebClient + a plain URL defers load balancing to the
   platform (Docker DNS, Kubernetes Service, service mesh). Consistency
   for its own sake isn't a reason to run redundant machinery.

2. **Why does putting `fallbackMethod` on the inner `@CircuitBreaker`
   instead of the outer `@Retry` break retry behavior, and how do you
   diagnose it went wrong?**
   *Look for:* a fallback intercepts *every* exception the aspect it's
   attached to sees; on the inner annotation, that means every single
   attempt, not just the final exhausted one. Diagnosis: count how many
   real HTTP requests actually landed for one logical call, compare
   against `max-attempts`, and check where the fallback log line appears
   relative to that count.

3. **Walk through the WireMock-test debugging story: why did a
   resilience test see 5 requests instead of 3, and whose bug was it?**
   *Look for:* it wasn't the production code — `TestRestTemplate`'s
   underlying Apache HttpClient 5 (pulled in transitively by the Eureka
   client dependency) silently retries any `503`/`429` response by
   default, so the test's own client resent the whole request after the
   server's first, entirely correct, `503`.

4. **Why does Employee Service throw a 503 when Department Service is
   unreachable, while Department Service returns a `null` headcount
   instead of failing the request?**
   *Look for:* the two calls check different kinds of truth. Department
   existence is a write-time business rule — proceeding on an unverifiable
   answer is unsafe. Headcount is supplementary statistics — failing an
   entire dashboard endpoint over one missing number is a worse trade-off
   than reporting "unknown."

5. **Why does `EmployeeHeadcountClientImpl` call `.block()` on a
   `Mono` inside a plain Spring MVC (servlet) application instead of
   returning the `Mono`/converting the whole service to WebFlux?**
   *Look for:* using `WebClient` as an HTTP client doesn't require
   adopting the reactive programming model end-to-end; `.block()` is safe
   because it executes on the caller's own request thread (a Tomcat
   worker), never on a Reactor Netty event-loop thread — the one context
   where blocking would actually be dangerous.

6. **What's the correct default `Retryer` for a Feign client that's
   also protected by Resilience4j's `@Retry`, and why?**
   *Look for:* `Retryer.NEVER_RETRY` — exactly one layer should own the
   retry decision; leaving Feign's own default retryer (5 attempts)
   active alongside Resilience4j's `@Retry` means every Resilience4j
   attempt can silently balloon into up to 5 real HTTP calls of its own.

## 8. Best Practices

- Pick a client-resolution strategy (declarative discovery-based load
  balancing vs. a platform-provided address) deliberately per
  integration, and be able to explain why — don't default to "whatever
  the last service used."
- Attach a Resilience4j `fallbackMethod` to the *outermost* aspect
  guarding a call, so it fires once, with the truly final exception —
  never to an inner aspect where it will intercept every individual
  attempt.
- Give every cross-service call an explicit connect and read timeout.
  Neither Feign nor WebClient waits "a sensible default" on their own;
  unconfigured, both will happily hang forever.
- Choose fail-loud vs. degrade-gracefully per call based on what the
  data actually means to the caller, not by copying whatever the last
  integration did.
- When a resilience test's counts don't match the configured policy,
  suspect the test's own HTTP client before the production code — test
  infrastructure can have its own hidden resilience behavior.
- Never let two independent retry mechanisms (a client library's
  built-in retryer and a resilience framework's `@Retry`) run against
  the same call unless that compounding is explicitly wanted and
  documented.

## 9. Common Mistakes

- Running both Feign's default retryer and Resilience4j's `@Retry`
  simultaneously, multiplying attempts without anyone intending it.
- Attaching `fallbackMethod` to `@CircuitBreaker` instead of `@Retry`
  when both are present, causing the fallback to fire on every attempt
  (or swallow the exception before `@Retry` ever sees a failure).
- Trusting a resilience test's request count without checking what the
  test's own HTTP client does with error responses — a client-side retry
  hiding in test infrastructure produces numbers that look like a
  production bug.
- Leaving a WebClient or Feign client's timeout unconfigured and
  discovering the "sensible default" is actually "wait forever" only
  once a dependency is genuinely slow in production.
- Returning a sentinel like `0` instead of `null` for "the dependency
  couldn't answer" — collapsing "unknown" and "genuinely zero" into the
  same value is a silent correctness bug waiting for a legitimately
  empty department to be reported as unreachable, or vice versa.
- Marking a Feign default-configuration class `@Configuration` when it
  lives inside the main application's scanned package, accidentally
  registering its beans a second time outside Feign's per-client
  isolation.

## 10. Summary

Employee Service and Department Service now call each other over real
HTTP for the first time, each wrapped in a Resilience4j policy chosen for
what the call actually means — fail loudly for a business rule, degrade
gracefully for supplementary statistics — and each proven correct against
a real embedded HTTP server (WireMock), not a mocked Java interface. The
step also surfaced a genuine, non-obvious bug class along the way: a
resilience test's own HTTP client silently retrying the very failure the
test exists to measure — a lesson worth more than the feature itself.

**Next step (Step 9): Event-driven architecture** — Kafka/RabbitMQ, the
Outbox Pattern, and Sagas. This is where Department Service's headcount
can move from "a live call on every statistics request" to "an
eventually-consistent local read model kept in sync by events," and where
services start reacting to each other's state changes instead of only
answering direct questions.
