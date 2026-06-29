# Plan 5 — Containerization & CI

**Goal:** Make the existing Kotlin/Spring Boot voucher-commerce backend reproducibly buildable and runnable as a container, bootable as a full local stack (app + MySQL + Redis), and continuously verified by GitHub Actions. Concretely: (1) externalize DB credentials / Redis host / JWT secret via environment variables so the same jar runs on a laptop and in a container; (2) a multi-stage `Dockerfile` (Gradle build stage → slim JRE runtime, non-root, expose 8080, `HEALTHCHECK` hitting `/actuator/health`); (3) extend the root `docker-compose.yml` to run `app + mysql + redis` with health-gated startup; (4) a `.github/workflows/ci.yml` that runs `./gradlew build` (incl. the Testcontainers test suite) and builds the image. This delivers the E2E / operational signal called for in spec §6.3.

**Architecture:**
- The Spring Boot app already reads its DB via `spring.datasource.*` and Redis via `spring.data.redis.*` (consumed by `RedisConfig.redissonClient()` through `RedisProperties`). We make those values come from env (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`) with the current local values as fallback defaults, so nothing changes for existing host-based `bootRun` / Testcontainers runs.
- The JWT secret is already wired as `@Value("\${jwt.secret:#{null}}")` in `JwtTokenProvider`. Spring's `SystemEnvironmentPropertySource` relaxed binding resolves the placeholder `jwt.secret` from the env var `JWT_SECRET`, so passing `JWT_SECRET` to the container is sufficient — no code or YAML change is needed for the secret.
- The runtime image runs the **layered** Spring Boot jar (`-Djarmode=layertools`) as a non-root user; the container `HEALTHCHECK` curls `/actuator/health` (already exposed via `management.endpoints.web.exposure.include: health,...`).
- `docker-compose.yml` (root) gains health-checked `mysql`/`redis` and a new `app` service that `depends_on` both being `service_healthy`, wiring the env above to the compose service names (`mysql`, `redis`).
- CI runs on `ubuntu-latest` (GitHub-hosted), which ships a working Docker daemon — so the Testcontainers MySQL+Redis integration/concurrency tests run unmodified.

**Tech Stack:** Gradle Kotlin DSL, Spring Boot 3.2.5, Kotlin 1.9.23, Java 17, Gradle 8.7 (wrapper). Containers: `eclipse-temurin:17-jdk-jammy` (build), `eclipse-temurin:17-jre-jammy` (runtime), `mysql:8.0`, `redis:7-alpine`. CI: GitHub Actions (`actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v3`, `docker/setup-buildx-action@v3`, `docker/build-push-action@v6`).

## Global Constraints

- **Package root** `com.commerce`. This plan touches **no Kotlin source** — only `src/main/resources/application.yml`, new root-level infra files, and `.github/`. **Plan 5 runs AFTER Plans 1–4 and builds on their changes** — it does NOT supersede them. By the time this plan runs, `application.yml` is already on `ddl-auto: validate` + `spring.flyway.enabled: true` + `baseline-on-migrate: true` (Plan 1, which also added the Flyway `V1`/`V2` migrations), carries `point.earn-rate` (Plan 3), and carries the `ai.promotion.*` block (Plan 4). This plan adds env-var indirection to the DB/Redis connection settings (with local-default fallbacks) and leaves all of those Plan 1–4 settings untouched.
- **Build artifact name is fixed** by `settings.gradle.kts` (`rootProject.name = "voucher-system"`) and `build.gradle.kts` (`version = "0.0.1-SNAPSHOT"`): the executable boot jar is exactly `build/libs/voucher-system-0.0.1-SNAPSHOT.jar` (the `*-plain.jar` is the non-executable one — never run it). Reference the boot jar by its exact name.
- **Single-test command** (contract): `./gradlew test --tests "com.commerce.<FqcnTest>"`. Full build: `./gradlew build`. Testcontainers tests (extending `com.commerce.support.IntegrationTestSupport`) **require a running Docker daemon**.
- **Actuator health** lives at `GET /actuator/health` and returns `{"status":"UP"}` when DB + Redis health indicators are up; it is `permitAll()` in `SecurityConfig`. Do not change the exposure list.
- **Preserve the schema-management config established by Plan 1** (`ddl-auto: validate`, `spring.flyway.enabled: true`, `baseline-on-migrate: true`). Do NOT revert any of these to `ddl-auto: update` / `flyway.enabled: false`. On first boot against a fresh MySQL volume the Flyway `V1`/`V2` migrations create the schema and Hibernate (`validate`) then verifies it — there is no `ddl-auto` schema creation.
- **Verification requires Docker.** Commands below are written for a POSIX shell (Git Bash on this Windows host); PowerShell equivalents are given for the health-wait loop. All paths are repo-root-relative; run from the repo root.
- **No placeholders / no secrets committed.** The only secret-shaped string is the existing local-dev JWT fallback already present in `JwtTokenProvider.kt`; real secrets are injected via env at runtime.

---

### Task 1: Externalize DB / Redis / JWT config via environment variables

Make `application.yml` read DB and Redis connection settings from env vars (defaulting to today's local values), and document the full env contract in `.env.example`. Add `.dockerignore` so later image builds get a small, deterministic build context. This task defines the env-var contract every later task consumes.

**Files:**
- Modify: `src/main/resources/application.yml` (lines 1–20 — the `spring.datasource` and `spring.data.redis` blocks)
- Create: `.env.example` (repo root)
- Create: `.dockerignore` (repo root)

**Interfaces:**
- Consumes (locked contract / existing code): `spring.datasource.url|username|password`, `spring.data.redis.host|port`, and `JwtTokenProvider`'s `@Value("\${jwt.secret:#{null}}")`.
- Produces (env-var contract used by Tasks 3 & 4): `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET` (resolved to `jwt.secret` via Spring relaxed binding), plus the compose-only `MYSQL_ROOT_PASSWORD`.

- [ ] **Step 1: (RED) Confirm config is currently hard-coded.** Run:
  ```bash
  grep -n "localhost" src/main/resources/application.yml
  ```
  Expected (FAIL state we are fixing): prints the hard-coded `jdbc:mysql://localhost:3306/voucher...` and `host: localhost` lines — i.e. no env indirection yet.

- [ ] **Step 2: Edit `application.yml` to read DB + Redis from env with local-value defaults.** Make a **targeted, surgical edit of only the five connection lines** — do NOT overwrite or regenerate the whole file. By the time this step runs, the file already contains `ddl-auto: validate`, `spring.flyway.enabled: true` + `baseline-on-migrate: true` (Plan 1), the `point.earn-rate` property (Plan 3), and the `ai.promotion.*` block (Plan 4); every one of those MUST survive this edit unchanged.

  Apply exactly this patch to the `spring.datasource` and `spring.data.redis` blocks (unchanged lines between the two hunks are elided with `…`):
  ```diff
   spring:
     datasource:
  -    url: jdbc:mysql://localhost:3306/voucher?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
  -    username: root
  -    password: root
  +    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:voucher}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
  +    username: ${DB_USERNAME:root}
  +    password: ${DB_PASSWORD:root}
       driver-class-name: com.mysql.cj.jdbc.Driver
  …
     data:
       redis:
  -      host: localhost
  -      port: 6379
  +      host: ${REDIS_HOST:localhost}
  +      port: ${REDIS_PORT:6379}
  ```
  Equivalently, as before/after snippets of just the lines that change (find-and-replace each):

  Before:
  ```yaml
      url: jdbc:mysql://localhost:3306/voucher?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
      username: root
      password: root
  ```
  After:
  ```yaml
      url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:voucher}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
      username: ${DB_USERNAME:root}
      password: ${DB_PASSWORD:root}
  ```
  Before:
  ```yaml
        host: localhost
        port: 6379
  ```
  After:
  ```yaml
        host: ${REDIS_HOST:localhost}
        port: ${REDIS_PORT:6379}
  ```
  Only these five lines (`datasource.url/username/password` and `data.redis.host/port`) change. Leave `ddl-auto: validate`, `spring.flyway.enabled: true`, `baseline-on-migrate: true`, `point.earn-rate`, the `ai.promotion.*` block, and everything else byte-for-byte as Plans 1–4 left it. Defaults equal today's hard-coded values, so host `bootRun` and Testcontainers — which override `spring.datasource.url` via `@DynamicPropertySource` — are unaffected.

- [ ] **Step 3: Create `.env.example` documenting the contract.** Full content:
  ```dotenv
  # Copy to ".env" for local docker compose runs. DO NOT commit a real .env.
  # docker compose auto-loads .env from the repo root.

  # --- MySQL (the "mysql" compose service) ---
  MYSQL_ROOT_PASSWORD=root
  DB_NAME=voucher

  # --- How the app reaches MySQL (compose sets DB_HOST=mysql for the app service) ---
  DB_HOST=mysql
  DB_PORT=3306
  DB_USERNAME=root
  DB_PASSWORD=root

  # --- How the app reaches Redis (compose sets REDIS_HOST=redis for the app service) ---
  REDIS_HOST=redis
  REDIS_PORT=6379

  # --- Security: HS256 signing key, MUST be >= 32 bytes. Resolves to jwt.secret. ---
  JWT_SECRET=change-me-to-a-256-bit-or-longer-random-secret-aaaaaaaaaaaa

  # --- AI (Plan 4, optional). App boots without it via the kill-switch; leave blank in CI. ---
  # ANTHROPIC_API_KEY=
  ```

- [ ] **Step 4: Create `.dockerignore` for a small, deterministic build context.** Full content:
  ```gitignore
  .git
  .gitignore
  .github
  .gradle
  build
  **/build
  load-test
  docs
  *.md
  .idea
  .vscode
  .env
  .env.*
  !.env.example
  Dockerfile
  docker-compose.yml
  ```

- [ ] **Step 5: (GREEN) Build the boot jar to prove the YAML still parses and the artifact name is what later tasks expect.** Run:
  ```bash
  ./gradlew clean bootJar --no-daemon
  ls build/libs/voucher-system-0.0.1-SNAPSHOT.jar
  ```
  Expected PASS: `BUILD SUCCESSFUL`, and `ls` prints `build/libs/voucher-system-0.0.1-SNAPSHOT.jar` (the executable boot jar referenced by the Dockerfile in Task 2). End-to-end proof that the env placeholders actually wire correctly happens in Task 3 (`/actuator/health` → `UP`).

- [ ] **Step 6: Commit.** Run:
  ```bash
  git add src/main/resources/application.yml .env.example .dockerignore
  git commit -m "build(config): externalize DB/Redis/JWT via env vars; add .env.example and .dockerignore"
  ```

---

### Task 2: Multi-stage Dockerfile (Gradle build → slim non-root JRE runtime)

Produce a reproducible image: a JDK build stage runs the Gradle wrapper to make the boot jar, and a slim JRE runtime stage runs the **layered** jar as a non-root user, exposes 8080, and declares a `HEALTHCHECK` against `/actuator/health`.

**Files:**
- Create: `Dockerfile` (repo root)

**Interfaces:**
- Consumes: the Gradle wrapper + scripts (`gradlew`, `gradle/`, `settings.gradle.kts`, `build.gradle.kts`), `src/`, the boot jar `build/libs/voucher-system-0.0.1-SNAPSHOT.jar`, and `GET /actuator/health` returning `{"status":"UP"}`.
- Produces: a runnable image (tagged `voucher-system:local` by Task 3 / `voucher-system:ci` by Task 4) that listens on `8080`, runs as user `appuser`, honors `JAVA_OPTS`, and reports container health.

- [ ] **Step 1: (RED) Confirm no Dockerfile exists yet.** Run:
  ```bash
  docker build -t voucher-system:local .
  ```
  Expected FAIL: `failed to read dockerfile: open Dockerfile: no such file or directory` (no `Dockerfile` present). This is the failing baseline we fix.

- [ ] **Step 2: Create `Dockerfile`.** Full content:
  ```dockerfile
  # syntax=docker/dockerfile:1

  # ========= Stage 1: build the boot jar with the Gradle wrapper =========
  FROM eclipse-temurin:17-jdk-jammy AS builder
  WORKDIR /workspace

  # Copy wrapper + build scripts first so the dependency layer caches across source edits.
  COPY gradlew settings.gradle.kts build.gradle.kts ./
  COPY gradle ./gradle
  RUN chmod +x ./gradlew && ./gradlew --no-daemon --version

  # Best-effort dependency warm-up (ignored if it cannot resolve without sources).
  RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

  # Copy sources and build the executable jar. Tests run in CI (Task 4), not in the image build.
  COPY src ./src
  RUN ./gradlew --no-daemon clean bootJar

  # Explode the layered Spring Boot jar for better runtime-image layer caching.
  RUN cp build/libs/voucher-system-0.0.1-SNAPSHOT.jar app.jar \
   && java -Djarmode=layertools -jar app.jar extract --destination extracted

  # ========= Stage 2: slim JRE runtime =========
  FROM eclipse-temurin:17-jre-jammy AS runtime
  WORKDIR /app

  # curl is used by the container HEALTHCHECK below.
  RUN apt-get update \
   && apt-get install -y --no-install-recommends curl \
   && rm -rf /var/lib/apt/lists/*

  # Run as a non-root system user.
  RUN groupadd --system appgroup \
   && useradd --system --gid appgroup --create-home appuser

  # Copy exploded layers, most-stable first (dependencies change least, application most).
  COPY --from=builder --chown=appuser:appgroup /workspace/extracted/dependencies/ ./
  COPY --from=builder --chown=appuser:appgroup /workspace/extracted/spring-boot-loader/ ./
  COPY --from=builder --chown=appuser:appgroup /workspace/extracted/snapshot-dependencies/ ./
  COPY --from=builder --chown=appuser:appgroup /workspace/extracted/application/ ./

  USER appuser

  # MaxRAMPercentage lets the JVM size the heap from the container memory limit.
  ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
  EXPOSE 8080

  HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

  # exec form so the JVM is PID 1 and receives SIGTERM for graceful shutdown.
  ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
  ```
  Notes baked in: Spring Boot 3.2 relocated the launcher to `org.springframework.boot.loader.launch.JarLauncher` (this is correct for 3.2.5); the layered-jar layer names (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`) are the Spring Boot defaults.

- [ ] **Step 3: (GREEN) Build the image.** Run:
  ```bash
  docker build -t voucher-system:local .
  ```
  Expected PASS: build completes with `naming to docker.io/library/voucher-system:local` and exit code 0.

- [ ] **Step 4: Verify non-root user and healthcheck are baked in.** Run:
  ```bash
  docker inspect --format 'user={{.Config.User}}' voucher-system:local
  docker inspect --format 'healthcheck={{json .Config.Healthcheck.Test}}' voucher-system:local
  ```
  Expected PASS: first line prints `user=appuser`; second prints a JSON array containing `curl -fsS http://localhost:8080/actuator/health` ... `"status":"UP"`. (A full boot-and-curl is exercised in Task 3.)

- [ ] **Step 5: Commit.** Run:
  ```bash
  git add Dockerfile
  git commit -m "build(docker): add multi-stage non-root Dockerfile with layered jar and actuator healthcheck"
  ```

---

### Task 3: Full-stack docker-compose (app + mysql + redis), health-gated

Extend the root `docker-compose.yml` so a single `docker compose up` boots the whole stack: health-checked MySQL and Redis, and an `app` service that waits for both to be healthy and reads its config from the env contract defined in Task 1.

**Files:**
- Modify: `docker-compose.yml` (repo root — currently only `mysql` + `redis`; lines 1–24)

**Interfaces:**
- Consumes: the `Dockerfile` (Task 2); the env-var contract (Task 1) `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD/REDIS_HOST/REDIS_PORT/JWT_SECRET`; `MYSQL_ROOT_PASSWORD`.
- Produces: compose services `mysql`, `redis`, `app`; the app published on host `:8080`; image tag `voucher-system:local`; named volume `mysql-data`.

- [ ] **Step 1: (RED) Confirm the compose file has no `app` service yet.** Run:
  ```bash
  docker compose config --services
  ```
  Expected FAIL state: prints only `mysql` and `redis` (no `app`). After this task it must also list `app`.

- [ ] **Step 2: Replace `docker-compose.yml` with the full-stack definition.** Full content:
  ```yaml
  services:
    mysql:
      image: mysql:8.0
      container_name: voucher-mysql
      ports:
        - "3306:3306"
      environment:
        MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
        MYSQL_DATABASE: ${DB_NAME:-voucher}
        MYSQL_CHARACTER_SET_SERVER: utf8mb4
        MYSQL_COLLATION_SERVER: utf8mb4_unicode_ci
      command: --default-authentication-plugin=caching_sha2_password
      volumes:
        - mysql-data:/var/lib/mysql
      healthcheck:
        test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-p${MYSQL_ROOT_PASSWORD:-root}"]
        interval: 10s
        timeout: 5s
        retries: 10
        start_period: 30s

    redis:
      image: redis:7-alpine
      container_name: voucher-redis
      ports:
        - "6379:6379"
      healthcheck:
        test: ["CMD", "redis-cli", "ping"]
        interval: 10s
        timeout: 3s
        retries: 10

    app:
      build:
        context: .
        dockerfile: Dockerfile
      image: voucher-system:local
      container_name: voucher-app
      depends_on:
        mysql:
          condition: service_healthy
        redis:
          condition: service_healthy
      environment:
        DB_HOST: mysql
        DB_PORT: 3306
        DB_NAME: ${DB_NAME:-voucher}
        DB_USERNAME: root
        DB_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
        REDIS_HOST: redis
        REDIS_PORT: 6379
        JWT_SECRET: ${JWT_SECRET:-local-dev-only-secret-key-minimum-256-bits-long-do-not-use-in-production!!}
        JAVA_OPTS: "-XX:MaxRAMPercentage=75.0"
      ports:
        - "8080:8080"
      restart: unless-stopped

  volumes:
    mysql-data:
  ```
  Note for the executor: to run **infra only** (host `bootRun` against containerized DB/Redis, the previous workflow), use `docker compose up mysql redis`. `docker compose up` now boots the full stack including `app`. If a host process already holds `:8080`, stop it first or the `app` port binding fails.

- [ ] **Step 3: (GREEN-1) Validate the merged config and service list.** Run:
  ```bash
  docker compose config --quiet && docker compose config --services
  ```
  Expected PASS: `config --quiet` exits 0 (valid YAML/schema); `--services` prints `mysql`, `redis`, and `app`.

- [ ] **Step 4: (GREEN-2) Boot the stack and assert health is `UP`.** Run (POSIX shell):
  ```bash
  docker compose up -d --build
  for i in $(seq 1 40); do
    if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
      echo "HEALTH_OK"; break
    fi
    echo "waiting ($i)…"; sleep 5
  done
  curl -s http://localhost:8080/actuator/health; echo
  docker compose ps
  ```
  PowerShell equivalent for the wait loop:
  ```powershell
  docker compose up -d --build
  $ok = $false
  foreach ($i in 1..40) {
    try { if ((Invoke-RestMethod http://localhost:8080/actuator/health).status -eq 'UP') { $ok = $true; break } } catch {}
    Start-Sleep 5
  }
  if ($ok) { 'HEALTH_OK' } else { 'HEALTH_FAILED' }
  docker compose ps
  ```
  Expected PASS: the loop prints `HEALTH_OK`; the curl prints `{"status":"UP"}`; `docker compose ps` shows `voucher-app`, `voucher-mysql`, `voucher-redis` all `running` (mysql/redis `healthy`). (On first boot against the fresh `mysql-data` volume the Flyway `V1`/`V2` migrations create the schema, then Hibernate `ddl-auto: validate` validates it; there is no `ddl-auto` schema creation.)

- [ ] **Step 5: Tear down.** Run:
  ```bash
  docker compose down -v
  ```
  Expected: containers removed and the `mysql-data` volume deleted (clean slate for the next run).

- [ ] **Step 6: Commit.** Run:
  ```bash
  git add docker-compose.yml
  git commit -m "build(compose): add health-gated app service for full-stack app+mysql+redis boot"
  ```

---

### Task 4: GitHub Actions CI (build + Testcontainers suite + image build)

Add a CI workflow that, on push/PR to `main`, runs `./gradlew build` (compiles + runs the full test suite, including the Testcontainers MySQL+Redis integration/concurrency tests) and then builds the Docker image. GitHub-hosted `ubuntu-latest` runners include a working Docker daemon, which Testcontainers requires.

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `./gradlew build` (runs `com.commerce.**` unit + Testcontainers tests extending `IntegrationTestSupport`); the `Dockerfile` (Task 2); the env-var contract (`JWT_SECRET`).
- Produces: a CI pipeline with jobs `build-and-test` and `docker-image`; uploaded `test-reports` artifact.

- [ ] **Step 1: (RED) Confirm the workflow does not exist.** Run:
  ```bash
  test -f .github/workflows/ci.yml && echo EXISTS || echo MISSING
  ```
  Expected FAIL state: prints `MISSING`.

- [ ] **Step 2: Create `.github/workflows/ci.yml`.** Full content:
  ```yaml
  name: CI

  on:
    push:
      branches: [ main ]
    pull_request:
      branches: [ main ]

  jobs:
    build-and-test:
      # ubuntu-latest ships a running Docker daemon, which Testcontainers
      # (IntegrationTestSupport: MySQL + Redis via @Testcontainers) requires.
      runs-on: ubuntu-latest
      steps:
        - name: Checkout
          uses: actions/checkout@v4

        - name: Set up JDK 17
          uses: actions/setup-java@v4
          with:
            distribution: temurin
            java-version: '17'

        - name: Set up Gradle
          uses: gradle/actions/setup-gradle@v3

        - name: Make gradlew executable
          run: chmod +x ./gradlew

        - name: Build and test
          run: ./gradlew build --no-daemon --stacktrace
          env:
            # CI never calls the real AI API (Plan 4 kill-switch); supply only a valid
            # >= 32-byte HS256 signing key so any JWT code path has a deterministic secret.
            JWT_SECRET: ci-only-secret-key-minimum-256-bits-long-aaaaaaaaaaaaaaaaaaaa

        - name: Upload test reports
          if: always()
          uses: actions/upload-artifact@v4
          with:
            name: test-reports
            path: build/reports/tests/test
            if-no-files-found: ignore

    docker-image:
      runs-on: ubuntu-latest
      needs: build-and-test
      steps:
        - name: Checkout
          uses: actions/checkout@v4

        - name: Set up Docker Buildx
          uses: docker/setup-buildx-action@v3

        - name: Build image (no push)
          uses: docker/build-push-action@v6
          with:
            context: .
            push: false
            tags: voucher-system:ci
            cache-from: type=gha
            cache-to: type=gha,mode=max
  ```

- [ ] **Step 3: (GREEN-1) Validate the workflow YAML parses.** Run:
  ```bash
  python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('YAML_OK')"
  ```
  Expected PASS: prints `YAML_OK` with no traceback. (If `python` is unavailable, use `python3`.)

- [ ] **Step 4: (GREEN-2) Lint the workflow with actionlint via Docker (no local install).** Run:
  ```bash
  docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color
  ```
  PowerShell equivalent:
  ```powershell
  docker run --rm -v "${PWD}:/repo" --workdir /repo rhysd/actionlint:latest -color
  ```
  Expected PASS: actionlint prints nothing and exits 0 (no schema/expression errors).

- [ ] **Step 5: Commit.** Run:
  ```bash
  git add .github/workflows/ci.yml
  git commit -m "ci: add GitHub Actions workflow (gradle build + testcontainers + docker image)"
  ```

---

## Done criteria (spec §6.3 verification)

- `docker build -t voucher-system:local .` succeeds and the image runs as non-root with a working `HEALTHCHECK` (Task 2 Steps 3–4).
- `docker compose up` boots `app + mysql + redis` and `GET /actuator/health` returns `{"status":"UP"}` (Task 3 Step 4).
- `.github/workflows/ci.yml` is valid (Task 4 Steps 3–4) and runs `./gradlew build` + the Testcontainers suite + an image build on GitHub-hosted runners (Docker available).
- DB credentials, Redis host, and the JWT secret are all injected via env vars, with local defaults preserving existing host/Testcontainers behavior (Task 1).
