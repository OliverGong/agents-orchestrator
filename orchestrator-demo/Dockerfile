# syntax=docker/dockerfile:1
#
# Multi-stage build for orchestrator-demo: the Maven build runs inside the
# same JDK 25 toolchain the project targets, and the runtime image ships only
# the JRE plus the Spring Boot fat jar produced by `./mvnw package`.

# ---- build stage: Maven wrapper build inside JDK 25 ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Copy the Maven project descriptor first so the dependency download layer is
# only invalidated when pom.xml changes (source edits reuse the layer cache).
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY src/ ./src/

RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -DskipTests package

# ---- runtime stage: JRE + fat jar ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as a non-root user; /app/config holds the control-plane state file and
# is meant to be mounted as a named volume for persistence across restarts.
RUN useradd --system --uid 1000 app \
    && mkdir -p /app/config \
    && chown -R app:app /app
USER app

COPY --from=build /build/target/orchestrator-demo-*.jar app.jar

# Control-plane state lives on a writable path (mount a volume here).
ENV ORCHESTRATOR_CONTROL_CONFIG_FILE=/app/config/control-plane.json

# The model backend (Ollama) typically runs on the Docker host, and the OTLP
# exporter points at the tempo service of docker/docker-compose.yml by default;
# both are overridable through the usual Spring environment variables.
ENV SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434 \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
