# Step 3 — Config Server (Spring Cloud Config)

## 1. Goal

Give every service its configuration — database URLs, per-environment
overrides, secrets — from one centrally managed, versioned source instead
of copy-pasting `application.yml` fragments across six services. Build it
before the first business service, because Employee, Department, and Auth
will all start up by asking this server "what is my configuration?"
instead of hardcoding it locally.

Concretely, in this step we:
- Stand up a Spring Cloud Config Server backed by a filesystem ("native")
  repository, with the actual config data version-controlled inside this
  same repo for course reproducibility.
- Secure it with HTTP Basic auth — it hands out database passwords and JWT
  secrets over REST, so this matters even more than it did for Eureka.
- Wire up symmetric encryption so secrets are never stored in plaintext,
  even in a version-controlled file, and prove the encrypt→store→serve→
  decrypt round trip actually works end to end.

## 2. Architecture Diagram

```
                        ┌───────────────────────────────┐
                        │          Config Server           │
                        │                                    │
                        │  config-repo/                       │
                        │   ├── application.yml   (shared)     │
                        │   ├── employee-service.yml            │
                        │   ├── department-service.yml           │
                        │   ├── auth-service.yml  ({cipher}...)   │
                        │   └── api-gateway.yml                    │
                        └───────────────┬──────────────────────┘
                                        │ GET /{app}/{profile}  (Basic auth)
                                        │ merges: {app}-{profile}.yml
                                        │       > {app}.yml
                                        │       > application-{profile}.yml
                                        │       > application.yml
              ┌──────────────────────────┼──────────────────────────┐
              ▼                          ▼                           ▼
     Employee Service              Department Service            Auth Service
     spring.config.import=          spring.config.import=         spring.config.import=
     configserver:http://...        configserver:http://...       configserver:http://...
     (fetched ONCE at startup,      (same)                        (same)
      cached; refresh on demand
      via /actuator/refresh)
```

Bootstrap ordering (the part everyone gets wrong first):

```
1. Config Server starts standalone — it needs nothing but its own repo.
2. Discovery Server starts standalone — it needs nothing either.
3. Business services start LAST. Each one:
   a. resolves a FIXED, well-known Config Server URL (not via Eureka —
      chicken-and-egg: it doesn't know how to reach Eureka until Config
      Server tells it the Eureka URL)
   b. fetches its config (including eureka.client.service-url...)
   c. THEN registers itself with Eureka using that config
```

## 3. Folder Structure

```
config-server/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/configserver/
    │   │   ├── ConfigServerApplication.java   # @EnableConfigServer
    │   │   └── security/
    │   │       └── ConfigServerSecurityConfig.java
    │   └── resources/
    │       ├── application.yml         # the Config Server's OWN config
    │       └── config-repo/            # config it SERVES to other services
    │           ├── application.yml     # shared by every service
    │           ├── employee-service.yml
    │           ├── department-service.yml
    │           ├── auth-service.yml    # contains a real {cipher} secret
    │           └── api-gateway.yml
    └── test/
        ├── java/com/enterprise/ems/configserver/
        │   └── ConfigServerApplicationTests.java
        └── resources/
            └── application-test.yml
```

The two `application.yml` files are easy to confuse and serve completely
different purposes: `config-server/src/main/resources/application.yml` is
how the Config Server configures *itself*; `config-repo/application.yml`
is what it *hands out* to every other service.

## 4. Explanation

### The problem this solves

Without a config server, every service has its own `application.yml` with
its own copy of `eureka.client.service-url`, its own logging pattern, its
own actuator exposure settings. Change the Eureka URL and you edit six
files across six repos/deploys. Worse: database passwords end up
scattered across every service's source tree, each one a separate place
that can leak.

**Real-world analogy:** think of this like a company's centralized HR
policy document instead of every department manager keeping their own
photocopy. Update the policy once, everyone reads the current version.
Departments can still have their own local addenda (per-service config),
but the shared rules live in exactly one place.

### How property layering actually works

For a request `GET /employee-service/docker`, Spring Cloud Config merges,
highest priority first:
1. `employee-service-docker.yml` (per-service, per-profile)
2. `employee-service.yml` (per-service, default)
3. `application-docker.yml` (shared, per-profile)
4. `application.yml` (shared, default)

This is exactly why `config-repo/application.yml` in this step holds only
things every service should share (actuator exposure, log pattern, Eureka
client URL), while `employee-service.yml` holds only what's specific to
that one service (its port, its datasource). Get this split wrong and you
either duplicate config everywhere or accidentally leak one service's
setting into every other service.

### Why native (filesystem) backend, not git, for this course

Spring Cloud Config's most commonly cited backend is **git**: the server
clones a Git repository and serves whatever's on a given branch/tag
("label"), which gives you free version history, `git blame` on config
changes, and PR review before a config change ships. That is the right
choice for most real organizations, run against a *separate*,
access-controlled config repository (never the same repo as application
source, so a config change doesn't require a code deploy pipeline).

For this course, embedding a second, nested Git repository inside the
monorepo we're teaching from is an artificial and fragile setup (clone
mechanics, `.gitignore` interactions, no real remote to push to). We
instead use the **native backend**, which reads plain files from a
filesystem location — here, bundled onto the server's own classpath so
the whole course stays reproducible with zero external setup on a fresh
clone. This is not a toy-only choice either: many real Kubernetes
deployments use the native backend pointed at a **ConfigMap-mounted
volume** (`file:/etc/config-repo`) precisely so config changes are a
`kubectl apply` away, without rebuilding a container image. Swapping
`CONFIG_REPO_LOCATION` from `classpath:/config-repo/` to
`file:/etc/config-repo` is the only change needed to move from
this course's setup to that production pattern — no code changes.

### Why secure the Config Server (more urgent than Eureka)

Eureka exposing service topology is bad. Config Server exposing
`GET /auth-service/default` unauthenticated hands out a **JWT signing
secret and a database password** to anyone who can reach the port. We
apply the same pattern as Eureka: HTTP Basic auth on everything except
`/actuator/health` and `/actuator/info`. Unlike Eureka, there's no
human-facing dashboard here at all — every endpoint is a machine-consumed
REST/JSON API with no session/cookie state, so CSRF protection (which
exists to stop a browser from replaying a victim's session cookie) doesn't
apply and is disabled outright, not just narrowly.

### Encryption — why `{cipher}` and not plaintext in a "private" repo

Even in a repository nobody outside the team can read, plaintext secrets
in version control are a liability: they persist in every clone, every
backup, every CI log that happens to print a file, and every past commit
forever (rotating the secret doesn't erase it from history). Spring Cloud
Config supports storing an encrypted value as `{cipher}<ciphertext>` and
decrypting it automatically, in memory, only at the moment it serves that
value to an authenticated client. The plaintext never touches disk.

We configured a symmetric key (`encrypt.key`) and used the server's own
`/encrypt` endpoint to produce the real ciphertext committed in
`config-repo/auth-service.yml`:
```
POST /encrypt   body: dev-only-jwt-signing-key-rotate-per-environment-never-reuse-across-envs
→ 63d72dc3ebe8e624aef67257d7e20dc165ce6787e11dc780659c13e66e4d4910591fe7affd1bb99...
```
That ciphertext is what's actually committed. `GET /auth-service/default`
(with valid credentials) returns the **decrypted plaintext** —
`auth-service` itself never needs to know encryption happened at all; it
just reads `security.jwt.secret` as a normal property. In production, the
symmetric key itself must come from a real secrets manager or a
Kubernetes Secret (`CONFIG_ENCRYPT_KEY` env var) — never a value checked
into source control, which would defeat the entire point.

### Why the Config Server registers with Eureka but clients don't discover it that way

Every other service in this platform will be *found* via Eureka. The
Config Server is the one exception: it registers with Eureka (so it shows
up on the dashboard, so its health is monitored like everything else), but
business services are never told to look it up *through* Eureka. They are
given a fixed URL (`CONFIG_SERVER_URL` env var, resolved via
`spring.config.import=configserver:http://...`) because a service needs
its configuration — including the very Eureka credentials it would need
to query Eureka — before it can do anything else. This bootstrap-ordering
constraint is a common interview trap: "why not just discover the Config
Server through Eureka like everything else?"

## 5. Implementation

Module: `config-server/`, added to the root `pom.xml` `<modules>`.

- **`ConfigServerApplication`** — `@EnableConfigServer` on top of
  `@SpringBootApplication`.
- **`ConfigServerSecurityConfig`** — HTTP Basic auth on everything except
  `/actuator/health/**` and `/actuator/info`; CSRF disabled entirely
  (stateless, non-browser API).
- **`application.yml`** (the server's own config) — `spring.profiles.active: native`
  selects the filesystem backend; `spring.cloud.config.server.native.search-locations`
  points at the bundled `config-repo/`; `encrypt.key` configures symmetric
  encryption; credentials and the encryption key come from environment
  variables with local-only defaults.
- **`config-repo/`** — `application.yml` (shared defaults: actuator
  exposure, logging pattern, Eureka client URL) plus one file per planned
  service (`employee-service.yml`, `department-service.yml`,
  `auth-service.yml` with its `{cipher}` JWT secret, `api-gateway.yml`),
  each holding the settings that service will actually consume once its
  own step builds it.
- **`Dockerfile`** — same multi-stage pattern as the Discovery Server:
  repo-root build context, non-root runtime user, `/actuator/health`
  `HEALTHCHECK`.
- **`infra/docker/docker-compose.yml`** — `config-server` now depends on
  `discovery-server` being healthy before it starts.

Generate a real encrypted value yourself:
```
mvn -pl config-server -am spring-boot:run

curl -u config-admin:change-me-in-production \
     -H "Content-Type: text/plain" \
     --data "some-secret-value" \
     http://localhost:8888/encrypt
```
Then fetch a service's merged configuration:
```
curl -u config-admin:change-me-in-production http://localhost:8888/employee-service/default
```

## 6. Testing

`ConfigServerApplicationTests` (random port, `native`+`test` profiles —
`@ActiveProfiles` **replaces** rather than appends to the active profile
list, so `native` has to be listed explicitly or the server falls back to
the git backend and fails fast with "You need to configure a uri for the
git repository") verifies the actual contract:

1. **Context loads.**
2. **`/actuator/health` reachable without credentials.**
3. **Config endpoints reject unauthenticated requests (401).**
4. **`/employee-service/default` returns both its own file's properties
   (`server.port=8081`) and the shared `application.yml`'s properties
   (the Eureka URL) — proving the layering actually merges, not
   overrides.**
5. **`/employee-service/default` and `/department-service/default` return
   different ports — proving service configs are isolated from each
   other.**
6. **`/auth-service/default`'s `security.jwt.secret` comes back as
   plaintext, never containing the literal `{cipher}` prefix — proving
   automatic decryption on serve.**
7. **A fresh `/encrypt` → `/decrypt` round trip returns the original
   plaintext.**

```
mvn -pl config-server -am verify
```
All 7 tests pass. This was verified against a genuinely running instance,
not mocked: the committed `{cipher}` value in `auth-service.yml` is real
ciphertext produced by this server's own `/encrypt` endpoint, and test 6
proves it decrypts correctly with the key configured in `application.yml`.

## 7. Interview Questions

1. **Why centralize configuration instead of letting each service keep
   its own `application.yml`?**
   *Look for:* single source of truth, no more N-file edits for one
   change, environment-specific overrides without rebuilding a jar,
   auditability.

2. **Explain Spring Cloud Config's property resolution order for
   `{app}-{profile}`.**
   *Look for:* per-app-per-profile > per-app > shared-per-profile >
   shared-default; the reasoning that more specific always wins.

3. **Why not just discover the Config Server via Eureka like every other
   service?**
   *Look for:* the bootstrap chicken-and-egg problem — a service needs
   config (including how to reach Eureka) before it can register with or
   query Eureka.

4. **How do you avoid storing secrets in plaintext in a config
   repository, and what's the actual security boundary this achieves?**
   *Look for:* `{cipher}` values + `encrypt.key`; the boundary is "the
   ciphertext at rest is useless without the key," not "nobody can read
   the file" — the key itself must live in a real secrets manager, not
   also in the repo.

5. **What's the operational trade-off between the git backend and the
   native/filesystem backend for a Config Server?**
   *Look for:* git gives history/PR review/rollback but requires a
   running git remote and clone-on-refresh; native is simpler and works
   well with a mounted ConfigMap/Secret volume in Kubernetes, but loses
   built-in version history (K8s/GitOps tooling would need to provide that
   instead).

## 8. Best Practices

- Split shared vs. per-service config deliberately — don't let
  service-specific values leak into the shared file "because it was
  convenient."
- Never commit a real `encrypt.key` or any plaintext secret — only
  `{cipher}...` values, with the key itself injected via environment
  variable/secrets manager per environment.
- Keep the Config Server itself secured at least as strictly as your most
  sensitive downstream service — it is a single point that can leak every
  other service's secrets at once.
- Treat the Config Server as platform infrastructure with its own
  health/monitoring, not an afterthought — if it's down, no new service
  instance can start.

## 9. Common Mistakes

- Storing a plaintext database password "temporarily" in a config repo —
  it never actually gets rotated to `{cipher}` later.
- Trying to have services discover the Config Server through Eureka,
  hitting the bootstrap ordering problem, and "solving" it by hardcoding
  Eureka URLs into every service instead of fixing the actual dependency
  direction.
- Putting environment-specific values (a `prod` database host) into the
  shared `application.yml` instead of an `application-prod.yml` override —
  this makes every environment silently share settings that should differ.
- Forgetting that `@ActiveProfiles` in tests replaces rather than
  appends — a very easy way to accidentally disable the exact backend
  configuration you meant to test.

## 10. Summary

We now have a secured, encrypted, tested Config Server that every future
service will import configuration from via
`spring.config.import=configserver:...` — no more per-service copies of
shared settings, and no more plaintext secrets in version control. The
layering model (shared vs. per-service, default vs. per-profile) is now
proven end to end with real requests, not just described.

**Next step (Step 4): API Gateway** — the single entry point clients use,
now that we have both a place to find services (Discovery Server) and a
place to get configuration (Config Server) to build it against.
