# API Gateway

Single entry point for every client. Routes to downstream services by
Eureka application name, verifies JWTs at the edge, rate-limits per client
IP, and applies a single CORS policy for the whole platform.

Full lesson: [`docs/architecture/04-api-gateway.md`](../docs/architecture/04-api-gateway.md).

## Run locally

Requires Discovery Server and Config Server running first (Steps 2-3):
```
mvn -pl discovery-server -am spring-boot:run &
mvn -pl config-server -am spring-boot:run &
mvn -pl api-gateway -am spring-boot:run
```

## Run via Docker Compose

```
docker compose -f infra/docker/docker-compose.yml up --build
```

## Test

```
mvn -pl api-gateway -am verify
```

## Routes, JWT policy, CORS, and rate limits

All business configuration lives on the Config Server, not in this
module — see `config-server/src/main/resources/config-repo/api-gateway.yml`.
