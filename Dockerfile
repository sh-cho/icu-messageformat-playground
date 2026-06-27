# --- 1. Frontend: build the React bundle into src/main/resources/static ---------
FROM node:26-bookworm AS frontend
# Node 26 no longer bundles corepack; install it to honor the pnpm pin.
RUN npm install -g corepack && corepack enable
WORKDIR /src
COPY . .
RUN cd frontend && pnpm install --frozen-lockfile=false && pnpm run build

# --- 2. Fat jar (frontend tasks skipped — assets already built above) -----------
FROM eclipse-temurin:21-jdk AS build
# .dockerignore strips .git, so build.gradle.kts can't run `git describe`; the
# version is passed in instead (defaults to 1.0.0-dev for a plain `docker build`).
ARG VERSION=1.0.0-dev
ENV VERSION=${VERSION}
WORKDIR /src
COPY --from=frontend /src /src
RUN ./gradlew --no-daemon buildFatJar -x buildFrontend -x installFrontend

# --- 3. Runtime: JRE only -------------------------------------------------------
# Kept on temurin (not distroless) on purpose: the AppCDS archive below is tied to
# the exact JDK build that trains it, so training and running must share one JDK.
# distroless ships a different OpenJDK build and would silently reject the archive.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/playground-all.jar /app/app.jar

# Train an AppCDS archive at build time. The headless pass (-Dheadless.warmup, defined
# in Application.kt) loads icu4j + the serialization classes then exits cleanly, so the
# JDK + Ktor + icu4j classes get captured into a memory-mappable archive. Each cold
# start then memory-maps the archive instead of parsing/loading those classes from the
# jar again — a big chunk of JVM cold start.
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa -Dheadless.warmup=true -jar /app/app.jar || true

# Drop root: run as an unprivileged, no-login user. The jar + .jsa stay root-owned and
# world-readable (the runtime only reads them), so no chown is needed.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser
USER 10001

# OCI metadata — links the published image back to the repo, commit and version.
ARG VERSION=1.0.0-dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="icu-playground" \
      org.opencontainers.image.description="ICU MessageFormat playground (icu4j) — JVM image" \
      org.opencontainers.image.source="https://github.com/sh-cho/icu-messageformat-playground" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Cold-start / small-container tuning:
#  -XX:SharedArchiveFile  use the AppCDS archive built above
#  -XX:+UseSerialGC       lowest-overhead GC for a 1-vCPU / small-heap container
#  -XX:TieredStopAtLevel=1  stop JIT at C1 — faster warmup, less compile CPU (peak
#                           throughput drops, which this workload never needs)
#  -XX:MaxRAMPercentage=75  size the heap from the container's memory limit
EXPOSE 8080
# No Docker HEALTHCHECK: point orchestrator probes at GET /api/health instead.
ENTRYPOINT ["java", \
    "-XX:SharedArchiveFile=/app/app.jsa", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:MaxRAMPercentage=75", \
    "-jar", "/app/app.jar"]
