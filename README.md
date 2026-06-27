# ICU MessageFormat Playground

Render ICU MessageFormat **with icu4j** in the browser. Unlike JS playgrounds that
rely on `intl-messageformat` (and the browser's `Intl` limits), this renders through
icu4j on the JVM, so the output matches what a JVM backend produces. icu4j bundles its
own CLDR data, so results are consistent regardless of host environment.

- **MF1** — `com.ibm.icu.text.MessageFormat`
- **MF2** — `com.ibm.icu.message2.MessageFormatter` (Technical Preview)

The whole thing ships as a **single fat jar** that serves both the API and the UI.

## Run it

### One-shot (production)

```bash
./gradlew buildFatJar
java -jar build/libs/playground-all.jar
# → http://localhost:8080
```

Only a JRE 21+ is required at runtime. The frontend is built and bundled automatically.

### Docker

Two images are available — pick by what you're optimizing for. Both run as a non-root
user and carry OCI labels (source, version, revision).

**JVM image** (`Dockerfile`) — the reliable default. Builds the fat jar, bakes an
AppCDS archive, and runs with cold-start/small-container JVM flags. Sub-second cold
start, ~100MB+ RSS. Runtime stays on `eclipse-temurin:21-jre` (not distroless) on
purpose: an AppCDS archive is tied to the exact JDK build that trains it, so a
different OpenJDK build would silently reject it.

```bash
docker build -t icu-playground .
docker run -p 8080:8080 icu-playground
```

**GraalVM native image** (`Dockerfile.native`) — opt-in fast path. Compiles a native
binary: ~50ms cold start, ~30–50MB RSS. The binary is mostly-static
(`-H:+StaticExecutableWithDynamicLibC`, zlib etc. linked in), so it runs on a minimal
`distroless/base` image — no package manager, no shell. The build is heavier (several
minutes, ~6GB RAM) and depends on icu4j reflection metadata captured by the tracing
agent during the build, so treat the JVM image as the source of truth and smoke-test
this one.

```bash
docker build -f Dockerfile.native -t icu-playground:native .
docker run -p 8080:8080 icu-playground:native
```

**Published images** (GHCR, built by the release workflow on a `v*` tag):

```bash
docker pull ghcr.io/sh-cho/icu-messageformat-playground:latest          # JVM (amd64 + arm64)
docker pull ghcr.io/sh-cho/icu-messageformat-playground:latest-native   # native (amd64)
```

### Dev mode

```bash
./gradlew run                       # backend on :8080
cd frontend && pnpm install && pnpm dev   # UI on :5173, proxies /api → :8080
```

## API

`POST /api/format`

```json
{ "engine": "mf1", "template": "{count, plural, one {# item} other {# items}}", "locale": "en-US", "args": { "count": 3 } }
```

Response is always HTTP 200. Success: `{ "output": "...", "error": null }`.
Failure: `{ "output": null, "error": { "type": "SYNTAX|MISSING_ARG|TYPE_MISMATCH|INTERNAL", "message": "...", "offset": 12 } }`.

`GET /api/locales` returns a curated shortlist for the dropdown (any BCP-47 tag is accepted).

`GET /api/health` returns `ok` (HTTP 200) — a cheap liveness/readiness probe. The slim
runtime images have no shell, so there's no in-image `HEALTHCHECK`; point your
orchestrator's probe (k8s, load balancer, Compose) at this endpoint instead.

### Argument coercion

JSON has no date type, so dates are tagged explicitly:

```json
{ "exp": { "@type": "date", "value": "2025-03-27T00:00:00Z" } }
```

`@type` may be `date` / `time` / `datetime` (ISO-8601 string or epoch millis),
`number`, `string`, or `boolean`. Plain JSON numbers keep their integer/float
distinction (it affects `plural` selection); strings/booleans are used for `select`.

## Toolchain

- JDK 21 bytecode (build runs on JDK 25 via mise), Kotlin 2.2, Ktor 3.3, icu4j 78.1
- Frontend: Vite + React 19 + CodeMirror 6, built with pnpm

## Test

```bash
./gradlew test
```

## CI/CD

- **`.github/workflows/ci.yml`** — on push/PR: runs the test suite (toolchain from
  `mise.toml`) and smoke-builds the JVM image so a broken Dockerfile fails the PR.
- **`.github/workflows/release.yml`** — on a `v*` tag: builds and pushes the JVM image
  (multi-arch amd64 + arm64) and the native image (amd64) to GHCR with SBOM + provenance
  attestations, then runs a Trivy vulnerability scan. The version is derived from the
  tag and forwarded to the build (`.dockerignore` strips `.git`, so it can't be inferred
  inside the image).

Cut a release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## License

MIT — see [LICENSE](LICENSE).
