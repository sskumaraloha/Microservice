# Step 5 — Authentication Service

## 1. Goal

Build the service that owns identity for the whole platform: registration,
login, token issuance, refresh-token rotation, role-based access control,
email verification and password reset (unified behind one OTP mechanism),
and a scaffolded path to Google OAuth2 login. This is the first service
with its own database (`auth_db`) and the first to demonstrate the
"trust the Gateway's headers, don't re-verify JWTs downstream" pattern
that every later service will also follow.

## 2. Architecture Diagram

```
                        Client
                          │
                          ▼
                    API Gateway (Step 4)
              verifies JWT signature/expiry
              adds X-Auth-User-Id / X-Auth-Roles
                          │
                          ▼
                    Auth Service
   ┌──────────────────────────────────────────────────────┐
   │  HeaderAuthenticationFilter                            │
   │    trusts X-Auth-* — does NOT re-verify the JWT          │
   │                          │                                │
   │  Controllers → Services → Repositories → auth_db (MySQL)   │
   │                                                              │
   │  AuthService          TokenService           OtpService      │
   │  register/login/      issues JWT access +    generates/       │
   │  refresh/logout/      opaque refresh          verifies 6-digit │
   │  verify/reset         tokens (hashed,         codes (BCrypt,    │
   │                       revocable)              scoped lookup)    │
   │                                                                  │
   │  GoogleOAuth2SuccessHandler (only wired up when real Google      │
   │  credentials are configured — see Section 4)                     │
   └──────────────────────────────────────────────────────────────────┘
```

Token issuance detail — why access and refresh tokens are fundamentally
different kinds of tokens, not the same thing with different lifetimes:

```
Access Token (JWT)                    Refresh Token (opaque)
──────────────────                    ───────────────────────
Stateless, self-verifying             Stateful — a database row is the
Gateway checks it with zero            only source of truth
  calls back to Auth Service          Auth Service must look it up to
Short-lived (15 min)                    accept it
Cannot be revoked before expiry        Long-lived (7 days)
  (this is the accepted trade-off)     Individually revocable at any time
                                        Rotated on every use (old one dies
                                          the moment a new one is issued)
```

## 3. Folder Structure

```
auth-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/auth/
    │   │   ├── AuthServiceApplication.java
    │   │   ├── domain/          # User, Role, RefreshToken, OtpCode, OtpPurpose
    │   │   ├── repository/      # Spring Data JPA
    │   │   ├── dto/             # request/response records
    │   │   ├── mapper/          # MapStruct: User -> UserResponse
    │   │   ├── service/         # AuthService, TokenService, OtpService, UserService
    │   │   │   └── impl/
    │   │   ├── controller/      # AuthController, UserController
    │   │   ├── security/        # JwtTokenProvider, HeaderAuthenticationFilter,
    │   │   │                    # GoogleOAuth2SuccessHandler, JwtProperties
    │   │   ├── notification/    # NotificationPort + LoggingNotificationAdapter
    │   │   ├── exception/       # domain exceptions + @RestControllerAdvice
    │   │   └── config/          # SecurityConfig, GoogleOAuth2SecurityConfig,
    │   │                        # PasswordEncoderConfig, OpenApiConfig
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/    # V1..V5 Flyway migrations
    └── test/
        ├── java/com/enterprise/ems/auth/
        │   ├── service/         # Mockito unit tests
        │   ├── security/        # filter + OAuth2 handler unit tests
        │   ├── controller/      # @WebMvcTest slice
        │   └── integration/     # H2 full-context + Testcontainers MySQL
        └── resources/application-test.yml
```

## 4. Explanation

### Access tokens vs. refresh tokens: why they are not the same thing

An access token is a JWT the Gateway verifies **without calling this
service at all** — that statelessness is exactly what makes the Gateway
fast and horizontally scalable. But statelessness has a cost: you cannot
revoke a JWT before it expires without maintaining a denylist (which
reintroduces the state you were trying to avoid). The fix used almost
universally in production: make access tokens short-lived (15 minutes
here) so a stolen one is only dangerous briefly, and make the **refresh**
token — the thing that gets a user a new access token — an **opaque,
server-tracked, individually revocable** value instead of another JWT.

`TokenServiceImpl` stores only a SHA-256 hash of the refresh token
(`token_hash` column), never the plaintext. SHA-256, a *fast* hash, is the
right choice **specifically because** a refresh token is 256 bits of
`SecureRandom` — nobody is brute-forcing that regardless of hash speed.
Contrast with OTP codes below, where the opposite reasoning applies.

### Refresh token rotation and reuse detection

Every successful `/refresh` call revokes the presented token and issues a
brand new one (`TokenServiceImpl.rotateRefreshToken`). If a refresh token
is ever presented a **second** time, that can only mean one of two things:
a client bug where the same token is used twice, or a stolen token being
replayed by an attacker after the legitimate user already rotated it. We
can't tell which — so we treat it as theft and revoke **every** active
refresh token for that user, forcing re-authentication everywhere. This
is a real technique used by, e.g., Auth0 and Okta, and is exactly the kind
of detail that separates a toy JWT tutorial from a production auth system.

### Why passwords AND OTP codes use BCrypt, but refresh tokens don't

The dividing line is **entropy**, not "is this a secret."
- A password or a 6-digit OTP code is low-entropy and human-chosen or
  small-range — an attacker with the hash and enough compute can brute
  force it unless the hash function is *deliberately slow* (BCrypt).
- A refresh token is 256 bits of cryptographic randomness — brute force
  is infeasible regardless of hash speed, and using a slow hash would only
  cost you server CPU on every single API call's implicit refresh check
  for nothing.

A second, subtler reason OTP codes use BCrypt safely despite BCrypt's
random per-hash salt (which makes "look up by hash" impossible): **we
never look up an OTP by its hash.** We already know the user and the
purpose (`EMAIL_VERIFICATION` or `PASSWORD_RESET`) from the request itself,
fetch that user's latest usable code, and call `passwordEncoder.matches()`
against that one row. Refresh tokens, by contrast, arrive with no other
identifying information — the token itself is the only lookup key — which
is precisely why they need a deterministic (if fast) hash instead.

### One OTP mechanism, two purposes

Rather than building separate "email verification token" and "password
reset token" subsystems, `otp_codes` carries a `purpose` column and one
`OtpService` serves both. Both are the same underlying primitive: a
short-lived, single-use secret proving control of an email address. This
is a deliberate simplification that removes duplicate code without
weakening either flow — the interview-question version of this is "how do
you recognize when two features are actually the same concept wearing
different names?"

### Same-error responses to prevent user enumeration

Two places in `AuthServiceImpl` deliberately throw the *same* error
regardless of which of two different problems actually occurred:
- `login`: "Invalid email or password" whether the email doesn't exist or
  the password is wrong.
- `forgotPassword`: **always** returns 202 Accepted, whether or not the
  email is registered — only the log line (never the HTTP response)
  reveals which branch ran.

Without this, an attacker could enumerate every registered email address
in the system one guess at a time using either endpoint's error/success
signal. This is OWASP-documented (User Enumeration) and one of the most
commonly *missed* details in hand-rolled auth systems.

### Trusting the Gateway instead of re-verifying JWTs here

`HeaderAuthenticationFilter` does exactly what Step 4 designed it to do on
the other side: read `X-Auth-User-Id`/`X-Auth-Roles` and populate Spring
Security's context with a `PreAuthenticatedAuthenticationToken` — no
signature check, no jjwt parsing. Auth Service still uses jjwt, but only
to **sign** tokens (`JwtTokenProvider`), never to parse one it received.
This keeps every downstream service (including this one, and Employee and
Department in Steps 6-7) identical in how they handle authentication:
trust the network boundary the Gateway enforces, don't duplicate
cryptographic verification everywhere.

### RBAC in practice: matcher order matters

`SecurityConfig` protects `/api/v1/users/**` with `hasRole("ADMIN")` —
but `/api/v1/users/me` must be reachable by **any** authenticated user, not
just admins. Spring Security evaluates `authorizeHttpRequests` matchers in
declaration order and uses the **first** match, so the more specific
`/api/v1/users/me` rule is declared *before* the broader `/api/v1/users/**`
rule. Getting this ordering backwards is an extremely common real-world
bug — it compiles, starts, and passes a cursory smoke test, then fails
the first time a non-admin calls `/me`. (We caught this on paper in this
very course and fixed it before writing a single test — a good example of
review before verification, not instead of it.)

### The OAuth2 Google login scaffold — and why it's conditional

`GoogleOAuth2SecurityConfig` only registers its `SecurityFilterChain` bean
when a `ClientRegistrationRepository` bean already exists — which Spring
Boot only creates once real credentials are configured (normally via the
`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENTID` /
`..._CLIENTSECRET` environment variables). We deliberately do **not** put
placeholder empty values for these in `application.yml`: Spring Security's
`ClientRegistration.Builder.build()` validates that `clientId` is
non-blank *at bean-creation time*, which is eager (happens during context
startup, not on first use) — so a blank client-id configured anywhere
would crash the entire service on boot, in every environment, including
this course's, where no real Google app is registered. Using
`@ConditionalOnBean(ClientRegistrationRepository.class)` means: no
credentials configured → no bean → no crash → Google login is simply
absent. Configure real credentials in a real deployment → the bean
appears → `.oauth2Login()` activates automatically on `/oauth2/**` and
`/login/**`, alongside (not instead of) the stateless JWT-header chain
that handles every other path.

`GoogleOAuth2SuccessHandler` runs only *after* Spring Security has already
completed the OAuth2 Authorization Code exchange with Google — it never
talks to Google itself. It finds-or-creates a **local** `User` row keyed
by the Google-verified email, marks `emailVerified = true` immediately
(Google already proved that), assigns a random unusable password hash
(federated accounts never log in with `/login`), and issues our own
access/refresh token pair — so every other part of the platform only ever
has to understand one token format, regardless of how the user originally
authenticated.

## 5. Implementation

Module: `auth-service/`, added to the root `pom.xml` `<modules>`. New
dependency management: `mysql-connector-j`, `flyway-core` +
`flyway-mysql`, `springdoc-openapi-starter-webmvc-ui` (2.6.0), Lombok and
MapStruct now actually wired as real dependencies (a gap from earlier
steps — the annotation-processor-only wiring wasn't enough for entity
classes to resolve `@Getter`/`@Setter` at compile time; fixed in the root
POM's `dependencyManagement`).

- **Flyway migrations** `V1`–`V5` — `users`, `roles` (seeded with
  ADMIN/MANAGER/EMPLOYEE), `user_roles`, `refresh_tokens`, `otp_codes`.
  Deliberately no `ON UPDATE CURRENT_TIMESTAMP` MySQL-specific syntax —
  Hibernate's `@UpdateTimestamp` already covers it, and portable DDL is
  what lets the same migrations run against H2 in tests.
- **Entities** — plain JPA + Lombok `@Getter`/`@Setter`, no field
  injection anywhere (constructor injection only, per this course's
  coding rules) on any Spring-managed class.
- **`JwtTokenProvider`** — signs access tokens with the same shared
  secret from Config Server the Gateway verifies against.
- **`TokenServiceImpl`** — opaque refresh tokens, SHA-256 hash for
  lookup, rotation with reuse detection (see Section 4).
- **`OtpServiceImpl`** — BCrypt-hashed, purpose-scoped, 10-minute TTL.
- **`AuthServiceImpl`** — register/login/refresh/logout/verifyEmail/
  forgotPassword/resetPassword, all with the enumeration-safe error
  handling described above.
- **`HeaderAuthenticationFilter`** + **`SecurityConfig`** — trusted-header
  RBAC, stateless session policy, CSRF disabled (no browser
  session/cookie exists for this API to forge).
- **`GoogleOAuth2SecurityConfig`** + **`GoogleOAuth2SuccessHandler`** —
  conditional OAuth2 Google login scaffold (Section 4).
- **`GlobalExceptionHandler`** — maps every domain exception to a
  consistent JSON error shape and the correct HTTP status (409 for
  duplicate email, 401 for bad credentials, 403 for a disabled account,
  400 for a bad/expired OTP or validation failure).
- **Swagger/OpenAPI** — `springdoc-openapi-starter-webmvc-ui`, browsable
  at `/swagger-ui.html` once the service is running.

## 6. Testing

**40 tests, all passing** (39 executed, 1 self-skipped without Docker):

- `TokenServiceImplTest` (6) — issuance, rotation, reuse-detection
  (asserts every *other* active session gets revoked too), unknown-token
  rejection, single and no-op revocation.
- `OtpServiceImplTest` (5) — code generation/hash/send, and rejection of a
  wrong code, an expired code, and an already-consumed code.
- `AuthServiceImplTest` (10) — registration (hashing, default role, OTP
  triggered), duplicate-email rejection, login success/failure paths
  (unknown email, wrong password, disabled account — all via the unit
  test, not just the integration test), forgot/reset password including
  the "silently do nothing for an unknown email" behavior, email
  verification.
- `HeaderAuthenticationFilterTest` (3) — populates `SecurityContext`
  correctly from trusted headers; leaves it empty with no header; treats
  a missing roles header as zero authorities rather than an error.
- `GoogleOAuth2SuccessHandlerTest` (2) — creates a federated account on
  first login, reuses the existing one (no duplicate) on a second login —
  entirely without contacting Google, by fabricating the post-exchange
  `OAuth2AuthenticationToken` directly.
- `AuthControllerTest` (6, `@WebMvcTest`, security filters disabled) —
  status codes and validation-error shapes at the controller boundary,
  independent of business logic (mocked `AuthService`).
- `AuthServiceApplicationIntegrationTest` (7, full Spring context, H2 in
  MySQL-compatibility mode, Flyway migrations actually applied) — the
  entire register → login → refresh → **replay-rejected** → verify-email
  flow over real HTTP; duplicate registration → 409; forgot/reset password
  → old refresh token now rejected, old password now rejected, new
  password works; and all three RBAC cases for `/api/v1/users`
  (unauthenticated → 401/403, wrong role → 403, ADMIN → 200).
- `MySqlIntegrationTest` (1, `@Testcontainers(disabledWithoutDocker = true)`) —
  re-validates Flyway migrations and the core register/login flow against
  a **real** MySQL container, catching anything that happens to work
  against H2's MySQL-compatibility mode but wouldn't against the actual
  database engine this service runs on everywhere else. This class
  self-skips (not fails) in this course's sandboxed environment, which has
  no Docker daemon; a real CI pipeline runs it for full coverage.

```
mvn -pl auth-service -am verify
```

## 7. Interview Questions

1. **Why not just make the refresh token a JWT too?**
   *Look for:* a JWT can't be revoked before expiry without a denylist,
   which defeats the purpose of using a stateless token in the first
   place; an opaque, DB-backed token can be revoked instantly and
   individually.

2. **What is refresh token rotation, and what does "reuse detection"
   actually protect against?**
   *Look for:* every refresh issues a new refresh token and kills the old
   one; a dead token being presented again is a signal of theft/replay,
   not something a legitimate client would ever do, so the correct
   response is to kill every session for that user, not just reject the
   one request.

3. **Why does this service hash passwords and OTP codes with BCrypt but
   refresh tokens with SHA-256? Isn't that inconsistent?**
   *Look for:* entropy is the deciding factor, not "is it a secret" —
   BCrypt's slowness matters only against low-entropy, brute-forceable
   values; a 256-bit random token gains nothing from a slow hash and pays
   for it on every request.

4. **Why does `/login` return the same error for "no such user" and
   "wrong password"?**
   *Look for:* preventing user enumeration — a different error per case
   lets an attacker discover which emails are registered one guess at a
   time.

5. **Why is the Google OAuth2 filter chain conditional on a bean's
   existence instead of just checking an `if` at runtime?**
   *Look for:* Spring Security validates OAuth2 client registrations
   (non-blank client-id) eagerly at context startup, not on first use —
   an `if` inside request-handling code would never even run before the
   application already failed to start with blank credentials configured.

## 8. Best Practices

- Keep access tokens short-lived and stateless; keep anything that must
  be revocable (refresh tokens, sessions) server-tracked.
- Match hashing strategy to the secret's entropy, not a blanket "always
  use BCrypt" or "always use SHA-256" rule.
- Return identical responses for "doesn't exist" and "exists but wrong"
  wherever the distinction would leak account existence.
- Make dangerous configuration states (blank OAuth2 credentials) fail to
  even construct, rather than fail at first use in production.

## 9. Common Mistakes

- Making refresh tokens JWTs "for consistency," then discovering you have
  no way to log a user out.
- Declaring a broad RBAC matcher before a narrower exception to it (the
  `/api/v1/users/me` vs. `/api/v1/users/**` ordering bug caught in this
  step) — always order specific rules before general ones.
- Leaking which branch a security-sensitive check took (login,
  forgot-password) via different status codes or response bodies.
- Wiring OAuth2 login unconditionally and letting a missing-credentials
  misconfiguration crash the whole service instead of just disabling one
  feature.

## 10. Summary

Auth Service now issues the tokens the Gateway has been verifying since
Step 4, using a deliberately asymmetric design (stateless JWT access
tokens, stateful revocable refresh tokens) that mirrors real production
systems rather than the simplified "one JWT for everything" tutorials
common online. Every downstream service built from here on trusts the
Gateway's headers instead of re-parsing tokens, keeping authentication
logic in exactly one place.

**Next step (Step 6): Employee Service** — the first service handling
real business data (CRUD, search, pagination, documents), owning
`employee_db`, and the first to actually call another service (Department
Service, via OpenFeign in Step 8) to validate a foreign reference.
