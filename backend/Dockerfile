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
 && useradd --system --uid 10001 --gid filegrid --home-dir /app --shell /usr/sbin/nologin filegrid
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/filegrid-backend.jar /app/filegrid-backend.jar

# Non-root. The JVM sizes itself from the container memory limit; the exact
# flags come from JAVA_TOOL_OPTIONS in docker-compose.
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/filegrid-backend.jar"]
