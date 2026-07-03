# Build context MUST be the repository root:
#   docker build -f auth-service/Dockerfile -t ems/auth-service:latest .

# ---- Build stage --------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY auth-service auth-service

RUN mvn -q -f pom.xml -pl auth-service -am package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/auth-service/target/auth-service.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8083/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
