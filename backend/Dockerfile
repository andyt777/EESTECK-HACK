# syntax=docker/dockerfile:1.7
# FileGrid backend image. The same image runs the api and every worker; the
# role is chosen at runtime with SPRING_PROFILES_ACTIVE.

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# The Maven repository is a BuildKit cache: dependencies are downloaded once.
# Tests run with `./mvnw verify` (they need Docker), not during the image build.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 filegrid \
 && useradd --system --uid 10001 --gid filegrid --home-dir /app --shell /usr/sbin/nologin filegrid \
 && mkdir -p /data/staging \
 && chown -R 10001:10001 /data/staging \
 && chmod 770 /data/staging
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/filegrid-backend.jar /app/filegrid-backend.jar

# Tracing (Stage 8): OpenTelemetry Java agent, pinned and checksum-verified
# (sha256 published on Maven Central). It traces HTTP, RabbitMQ (traceparent
# header: api -> queue -> worker), JDBC and the AWS SDK. Configured with OTEL_*
# (endpoint, service name) in docker-compose; OTEL_JAVAAGENT_ENABLED=false turns
# it off. Metrics stay on Micrometer/Prometheus and logs on stdout -> Alloy -> Loki,
# so only traces are exported; the trace/span ids go into the log MDC as
# traceId/spanId.
ARG OTEL_AGENT_VERSION=2.33.1
ADD --checksum=sha256:e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149 --chmod=444 \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
    /opt/otel/opentelemetry-javaagent.jar
ENV OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none \
    OTEL_INSTRUMENTATION_COMMON_LOGGING_TRACE_ID_KEY=traceId \
    OTEL_INSTRUMENTATION_COMMON_LOGGING_SPAN_ID_KEY=spanId

# Non-root. The JVM sizes itself from the container memory limit; the exact
# flags come from JAVA_TOOL_OPTIONS in docker-compose.
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-javaagent:/opt/otel/opentelemetry-javaagent.jar", "-jar", "/app/filegrid-backend.jar"]
