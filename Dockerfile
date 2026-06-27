# Build the fat jar (frontend is built and bundled by the buildFrontend Gradle task).
FROM node:26-bookworm AS build
RUN corepack enable
# JDK for the Gradle build
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon buildFatJar

# Runtime: JRE only.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/playground-all.jar /app/app.jar

# Train an AppCDS archive at build time. The app warms icu4j at boot (warmIcu()),
# so a brief run + clean shutdown captures the JDK + Ktor + icu4j classes into a
# memory-mappable archive. Each cold start then memory-maps the archive instead of
# parsing/loading those classes from the jar again — a big chunk of JVM cold start.
RUN java -XX:ArchiveClassesAtExit=/app/app.jsa -Dwarmup.exit=true -jar /app/app.jar || true

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
