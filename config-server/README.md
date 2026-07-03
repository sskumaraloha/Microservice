# Config Server

Spring Cloud Config Server. Centralizes configuration for every
microservice in the platform so environment-specific settings and secrets
live in one versioned, auditable place instead of being duplicated inside
every service's `application.yml`.

Full lesson: [`docs/architecture/03-config-server.md`](../docs/architecture/03-config-server.md).

## Run locally

```
mvn -pl config-server -am spring-boot:run
```

Fetch a service's merged configuration:
```
curl -u config-admin:change-me-in-production http://localhost:8888/employee-service/default
```

Encrypt a new secret before adding it to `config-repo/*.yml`:
```
curl -u config-admin:change-me-in-production \
     -H "Content-Type: text/plain" \
     --data "some-secret-value" \
     http://localhost:8888/encrypt
```

## Run via Docker Compose

```
docker compose -f infra/docker/docker-compose.yml up --build config-server
```

## Test

```
mvn -pl config-server -am verify
```

## Adding configuration for a new service

Drop a `{service-name}.yml` (and, if needed, `{service-name}-{profile}.yml`)
into `src/main/resources/config-repo/`. Anything that should apply to
every service instead belongs in `config-repo/application.yml`.
