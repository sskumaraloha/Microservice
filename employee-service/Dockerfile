# Build context MUST be the repository root:
#   docker build -f employee-service/Dockerfile -t ems/employee-service:latest .

# ---- Build stage --------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY employee-service employee-service

RUN mvn -q -f pom.xml -pl employee-service -am package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/employee-service/target/employee-service.jar app.jar
RUN mkdir -p /var/lib/ems/employee-documents && chown -R spring:spring /var/lib/ems/employee-documents app.jar
USER spring:spring

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
