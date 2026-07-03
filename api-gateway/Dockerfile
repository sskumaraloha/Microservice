# Build context MUST be the repository root:
#   docker build -f api-gateway/Dockerfile -t ems/api-gateway:latest .

# ---- Build stage --------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY api-gateway api-gateway

RUN mvn -q -f pom.xml -pl api-gateway -am package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
