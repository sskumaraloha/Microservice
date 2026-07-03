# Discovery Server

Netflix Eureka service registry. Every other microservice in this platform
registers itself here and discovers its peers by logical application name
instead of a hardcoded host:port.

Full lesson: [`docs/architecture/02-discovery-server.md`](../docs/architecture/02-discovery-server.md).

## Run locally

```
mvn -pl discovery-server -am spring-boot:run
```

Dashboard: http://localhost:8761 (Basic auth — see `EUREKA_SERVER_USERNAME`
/ `EUREKA_SERVER_PASSWORD`, defaults to `eureka-admin` / `change-me-in-production`
for local dev only).

## Run via Docker Compose

```
docker compose -f infra/docker/docker-compose.yml up --build discovery-server
```

## Test

```
mvn -pl discovery-server -am verify
```

## HA (peer-aware) mode

Run two instances with `SPRING_PROFILES_ACTIVE=peer1` and
`SPRING_PROFILES_ACTIVE=peer2` respectively (hostnames `peer1`/`peer2` must
resolve to each other, e.g. via Docker Compose service names or Kubernetes
Service names) to get a two-node replicated registry instead of a single
point of failure.
