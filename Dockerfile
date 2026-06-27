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
COPY --from=build /src/build/libs/playground-all.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
