# Build context MUST be the repository root (not this directory), because
# this is a Maven multi-module reactor and the build needs the parent POM:
#
#   docker build -f discovery-server/Dockerfile -t ems/discovery-server:latest .

# ---- Build stage --------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY discovery-server discovery-server

RUN mvn -q -f pom.xml -pl discovery-server -am package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/discovery-server/target/discovery-server.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring

EXPOSE 8761

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8761/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
