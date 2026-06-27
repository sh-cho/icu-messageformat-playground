# --- 1. Frontend: build the React bundle into src/main/resources/static ---------
FROM node:26-bookworm AS frontend
# Node 26 no longer bundles corepack; install it to honor the pnpm pin.
RUN npm install -g corepack && corepack enable
WORKDIR /src
COPY . .
RUN cd frontend && pnpm install --frozen-lockfile=false && pnpm run build

# --- 2. Fat jar (frontend tasks skipped — assets already built above) -----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY --from=frontend /src /src
RUN ./gradlew --no-daemon buildFatJar -x buildFrontend -x installFrontend

# --- 3. Runtime: JRE only -------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/playground-all.jar /app/app.jar

# Train an AppCDS archive at build time. The headless pass (-Dheadless.warmup, defined
# in Application.kt) loads icu4j + the serialization classes then exits cleanly, so the
# JDK + Ktor + icu4j classes get captured into a memory-mappable archive. Each cold
# start then memory-maps the archive instead of parsing/loading those classes from the
# jar again — a big chunk of JVM cold start.
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa -Dheadless.warmup=true -jar /app/app.jar || true

# Cold-start / small-container tuning:
#  -XX:SharedArchiveFile  use the AppCDS archive built above
#  -XX:+UseSerialGC       lowest-overhead GC for a 1-vCPU / small-heap container
#  -XX:TieredStopAtLevel=1  stop JIT at C1 — faster warmup, less compile CPU (peak
#                           throughput drops, which this workload never needs)
#  -XX:MaxRAMPercentage=75  size the heap from the container's memory limit
EXPOSE 8080
ENTRYPOINT ["java", \
    "-XX:SharedArchiveFile=/app/app.jsa", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:MaxRAMPercentage=75", \
    "-jar", "/app/app.jar"]
