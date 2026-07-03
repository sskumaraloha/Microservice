# Auth Service

Identity and access for the platform: registration, login, JWT access
tokens + revocable refresh tokens, RBAC, email verification and password
reset via OTP, and a conditionally-activated OAuth2 Google login scaffold.
Owns `auth_db` exclusively.

Full lesson: [`docs/architecture/05-auth-service.md`](../docs/architecture/05-auth-service.md).

## Run locally

Requires Discovery Server, Config Server, and a MySQL instance for
`auth_db` (or run everything via Docker Compose below):
```
mvn -pl auth-service -am spring-boot:run
```
Swagger UI: http://localhost:8083/swagger-ui.html

## Run via Docker Compose

```
docker compose -f infra/docker/docker-compose.yml up --build
```

## Test

```
mvn -pl auth-service -am verify
```

The Testcontainers-backed `MySqlIntegrationTest` needs a Docker daemon; it
self-skips (does not fail) when none is available.

## Enabling Google OAuth2 login

Not configured by default (see the Step 5 lesson for why). To enable it:
```
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENTID=your-client-id
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENTSECRET=your-client-secret
```
Then visit `/oauth2/authorization/google`.
