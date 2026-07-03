# Build context MUST be the repository root:
#   docker build -f department-service/Dockerfile -t ems/department-service:latest .

# ---- Build stage --------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY department-service department-service

RUN mvn -q -f pom.xml -pl department-service -am package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/department-service/target/department-service.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8082/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
