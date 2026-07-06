# --- 1. Frontend: build the React bundle into src/main/resources/static ---------
FROM node:26-bookworm@sha256:35d3b83382381e0e2f1d066b98aba486a4fab481a241c7516389635b88d927c1 AS frontend
# Node 26 no longer bundles corepack; install it to honor the pnpm pin.
RUN npm install -g corepack && corepack enable
WORKDIR /src
COPY . .
RUN cd frontend && pnpm install --frozen-lockfile && pnpm run build

# --- 2. Fat jar (frontend tasks skipped — assets already built above) -----------
FROM eclipse-temurin:25-jdk@sha256:68868d04fa9cfd5f5c6abec0b5cef86d8de2bf9c62c37c7d3e4f0f80f5cfd7ff AS build
# .dockerignore strips .git, so the version is passed in (defaults to 1.0.0-dev).
ARG VERSION=1.0.0-dev
ENV VERSION=${VERSION}
WORKDIR /src
COPY --from=frontend /src /src
RUN ./gradlew --no-daemon buildFatJar -x buildFrontend -x installFrontend

# --- 3. Runtime: JRE only -------------------------------------------------------
# Stay on temurin (not distroless): the AppCDS archive is tied to its exact JDK build,
# so a different OpenJDK would silently reject it.
FROM eclipse-temurin:25-jre@sha256:d0eb1b9018b3044da1b7346f39e945f71095749853d69a3aa16b8c99dad9bb45
# Adoptium bundles /usr/bin/pebble (a Go binary) we never invoke — the ENTRYPOINT calls java
# directly. Drop it so its transitive golang.org/x/net + stdlib CVEs don't trip the Trivy gate.
RUN rm -f /usr/bin/pebble
WORKDIR /app
COPY --from=build /src/build/libs/playground-all.jar /app/app.jar

# Train an AppCDS archive: the headless pass (-Dheadless.warmup, see Application.kt) loads
# the JDK/Ktor/icu4j classes so each cold start memory-maps them instead of reloading.
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa -Dheadless.warmup=true -jar /app/app.jar || true

# Unprivileged no-login user. The jar + .jsa stay root-owned and world-readable
# (runtime only reads them), so no chown needed.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser
USER 10001

ARG VERSION=1.0.0-dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="icu-playground" \
      org.opencontainers.image.description="ICU MessageFormat playground (icu4j) — JVM image" \
      org.opencontainers.image.source="https://github.com/sh-cho/icu-messageformat-playground" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Cold-start / small-container tuning: AppCDS archive, low-overhead SerialGC, C1-only JIT
# (faster warmup, lower peak throughput we don't need), heap sized from the container limit.
EXPOSE 8080
# No Docker HEALTHCHECK: point orchestrator probes at GET /api/health instead.
ENTRYPOINT ["java", \
    "-XX:SharedArchiveFile=/app/app.jsa", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:MaxRAMPercentage=75", \
    "-jar", "/app/app.jar"]
