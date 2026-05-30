# PROGRESS.md — Code Forger

This file is maintained by Claude Code.
Updated after every completed step.
Read at the start of every session.

---

## 📊 Overall Progress

**Steps completed:** 15.5 / 25
**Current phase:** Deployment & Shell setup
**Next step:** Step 15.5 — Backend Deployment Architecture & Security

---

## ✅ Completed Steps

### Step 15.5 — Backend Deployment & Security Design
- Status: DONE
- Notes: Designed the multi-stage Dockerfile strategy for exploded classpath resolution on PaaS, defined the passcode-based authentication scheme for REST/WebSockets, and mapped the CD workflow via GitHub Actions.
- Completed: 2026-05-30

### Step 1 — Monorepo folder structure
- Status: DONE
- Notes: `backend/` skeleton with `com.codeforger.{agent,config,controller,dto,model,orchestrator,service,websocket}` packages.

### Step 2 — Git init, .gitignore, GitHub remote
- Status: DONE
- Notes: Remote connected, initial commits pushed.

### Step 3 — GitHub Actions CI workflow
- Status: DONE
- Files: `.github/workflows/ci.yml`
- Notes: JDK 21 (Temurin), Maven cache, `./mvnw clean verify`. Separate commitlint job on PRs.

### Step 4 — Branch protection setup
- Status: DONE
- Notes: Rule applied to `master` via GitHub UI.

### Step 5 — Commitlint + Husky
- Status: DONE
- Files: `package.json`, `commitlint.config.cjs`, `.husky/commit-msg`
- Notes: `husky` v9 + `@commitlint/cli` + `config-conventional`. `commit-msg` hook runs `npx commitlint --edit`. Verified: bad msg rejected, good msg accepted.

### Step 6 — Spring Boot project init
- Status: DONE
- Files: `backend/pom.xml`, `backend/src/main/java/com/codeforger/CodeForgerApplication.java`, `backend/src/main/resources/application.yml`, `backend/src/test/java/com/codeforger/CodeForgerApplicationTests.java`, Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
- Notes: Spring Boot **4.0.6** on Java 21. Starters: `webmvc`, `websocket`, `validation` (+ matching `-test` modules). Verified locally with `./mvnw verify` — context-loads test passes, executable jar built.

---

### Step 11 — Google AI + Gemma config

- Status: DONE (branch `feat/phase2-spring-ai-config`, commit 52f2699 — PR not yet opened, stacking Step 12 on top)
- Files: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `backend/src/main/java/com/codeforger/config/AiConfig.java`
- Notes: Spring AI **2.0.0-M8** BOM (milestone — the only line compatible with Spring Boot 4 / Spring 7; 1.0 GA targets Boot 3 only). Artifact `spring-ai-starter-model-google-genai` (direct Google AI Studio, not Vertex). Property namespace is `spring.ai.google.genai.*` (matches auto-config package). `api-key` reads `GOOGLE_AI_API_KEY` env with placeholder fallback so context loads in CI; Spring AI eagerly creates `GoogleGenAiClient` at startup so a blank key fails bean creation. Single shared `ChatClient` bean for all agents. `./mvnw verify` green (5 tests).
- Risk to track: M8 API may shift before 2.0 GA — pin version, expect minor touch-ups when GA lands.

### Step 12 — Parser agent (Agent 1)

- Status: DONE (branch `feat/phase2-spring-ai-config`, commit 4727352)
- Files: `agents/ParserAgent.java`, `agents/SpecFetchException.java`, `agents/SpecParseException.java`, `model/ApiSchema.java`, `resources/prompts/parser.st`, test `agents/ParserAgentTest.java`. Also: `pom.xml` (+ spring-retry 2.0.10), `config/AiConfig.java` (+ RestClient bean).
- Notes:
  - `ApiSchema` started **minimal** — basePackage + entities + endpoints. Will grow as Agents 2/3 reveal what they need (YAGNI for now).
  - Uses **Spring AI `.entity(ApiSchema.class)`** for structured output — Spring AI auto-generates JSON schema from the record and parses the LLM response. Prompt doesn't have to describe the shape.
  - Prompt lives in [`prompts/parser.st`](backend/src/main/resources/prompts/parser.st) (separated from Java for easy iteration).
  - **Programmatic `RetryTemplate`** (3 attempts, exponential backoff 1s→2s→4s, cap 8s). `SpecParseException` classified non-retryable to fail fast on bad JSON. Fetch errors not retried — treated as user error.
  - **Spring Boot 4 quirk:** `RestClient.Builder` is NOT auto-provided here (legacy `spring-boot-starter-web` autoconfig coupling). Worked around by exposing `RestClient` as a `@Bean` in `AiConfig`. Worth re-checking when Boot 4 GA stabilizes.

### Step 13 — Code Generator agent (Agent 2)

- Status: DONE (branch `feat/phase2-spring-ai-config`, stacking on Steps 11–12)
- Files: `agents/CodeGeneratorAgent.java`, `agents/CodeGenerationException.java`, `agents/CompileError.java`, `model/GeneratedCode.java`, `resources/prompts/code-generator.st`, `resources/prompts/code-generator-correction.st`, test `agents/CodeGeneratorAgentTest.java`.
- Notes:
  - Two methods: `generate(ApiSchema)` and `correct(GeneratedCode previous, List<CompileError> errors)`. Both route through one private `callLlm()` using the shared `RetryTemplate` (3 attempts, exp backoff, `CodeGenerationException` non-retryable).
  - `GeneratedCode` is a record wrapping `Map<String, String>` (path-including filename → Java source). Path-prefixed filenames (`com/petstore/controller/PetController.java`) so the future ZIP writer can lay them out directly.
  - Two prompt templates instead of conditionals in one. Correction prompt is tightly scoped: "fix only the failing lines, preserve everything else" — mirrors Architecture.md reflection-loop wording.
  - `CompileError(file, line, message)` placeholder lives in `agents/` for now. Step 14's Validator will own the canonical shape — likely expand in place rather than move.
  - Spring AI `.entity(GeneratedCode.class)` for structured output; record-with-Map schema seems to round-trip fine in tests.
  - 4 new unit tests (clean response, retry-then-succeed, fail-fast on parse error, correct() passes prior code + errors into the prompt). Full suite: 13/13 green.

### Step 14 — Validator agent (Agent 3)

- Status: DONE (branch `feat/phase2-spring-ai-config`, stacking on Steps 11–13)
- Files: `agents/ValidatorAgent.java`, `agents/ValidationResult.java`, `agents/ValidationException.java`, test `agents/ValidatorAgentTest.java`. `agents/CompileError.java` from Step 13 promoted in place — shape already matched what `javax.tools.Diagnostic` exposes.
- Notes:
  - In-process `javax.tools.JavaCompiler` + `DiagnosticCollector<JavaFileObject>` + `StandardJavaFileManager`. No subprocess, no PATH dependency, structured diagnostics with line numbers.
  - Compile-only sandbox. We do **not** load or execute generated classes — `ClassLoader` never touches the output. Class files written to `<tempdir>/__out` purely so the compiler has somewhere to put them; dir is recursively deleted in `finally`.
  - Classpath = current JVM classpath (`System.getProperty("java.class.path")`) so the compiler sees Spring/JPA on our deps. `-proc:none` to disable annotation processing for now (deterministic, no Lombok processor wired yet).
  - Path traversal guard: every target path is `.normalize()`-d and verified to `startsWith(tempDir)` before writing — defends against `../`-laden filenames from the LLM.
  - `ValidationResult` is a record with `pass()` / `fail(errors)` factories. Reflection loop itself lives in the orchestrator (Step 15) — keeps the agent pure (one input → one output, no retry state).
  - 3 unit tests: clean compile, missing-semicolon error (line number captured), mixed batch (errors point to the right file). Full suite: 16/16 green.
- Known limitation to address in Step 15: if Agent 2 emits Lombok-annotated code, this validator will report false-positive "cannot find symbol" errors for the generated getters/setters. Two fixes available — add Lombok to pom + wire its annotation processor, or constrain Agent 2's prompt to plain getters/setters. Decide once we see real end-to-end output.

### Step 15 — Orchestrator + reflection loop + WebSocket broadcasting

- Status: DONE
- Files: `orchestrator/AgentOrchestrator.java`, updated `api/GeneratorController.java`, updated `pom.xml`, tests `orchestrator/AgentOrchestratorTest.java` and `api/GeneratorControllerTest.java`.
- Notes:
  - `AgentOrchestrator` is a Spring `SmartLifecycle` component with one daemon worker thread. It polls `JobQueue`, runs Parser → CodeGenerator → Validator, and stores completed files/errors back on `GenerationJob`.
  - Owns the reflection loop: initial validation, then up to 3 correction attempts via `codeGenerator.correct(previous, errors)`, then `FAILED` with compiler diagnostics if still invalid.
  - Broadcasts `QUEUED`, `PARSING`, `GENERATING`, `VALIDATING`, `CORRECTING`, `COMPLETE`, and `FAILED` through `StatusBroadcaster`; removed the temporary `QUEUED` broadcast from `GeneratorController`.
  - Added `codeforger.orchestrator.enabled=false` in controller tests so the 202 Accepted behavior remains deterministic while production defaults to enabled.
  - Added Surefire `-javaagent` config for Mockito 5.20.0 because Fedora/Temurin Java 21 blocks Mockito self-attachment. `JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk mvn verify` is green: 19 tests.

## 🔄 Current Step

### Step 15.5 — Backend Deployment Architecture & Security
- Status: IN PROGRESS
- What to build: Implement the `Dockerfile` for exploded classpath deployment, `railway.json` for platform config, GitHub Actions `cd.yml` for automated deployment, and the passcode-based `SecurityConfig` (REST Filter + WebSocket Interceptor).

## ⏳ Remaining Steps

### Phase 3 (3 remaining)

- Step 16 — Shell app + Module Federation config
- Step 17 — Runtime remote loading + GitHub Pages deploy

### Phase 4 (2 remaining)

- Step 18 — Agent Console MFE + WebSocket connection
- Step 19 — Live terminal UI component

### Phase 5 (3 remaining)

- Step 20 — Code Vault MFE + syntax highlighting
- Step 21 — ZIP download
- Step 22 — Backend polling for job result

### Phase 6 (3 remaining)

- Step 23 — End-to-end wiring + CORS
- Step 24 — Error handling
- Step 25 — README + demo script

---

## 📝 Session Notes

- **2026-05-28:** Spring Initializr now defaults to Spring Boot **4.0.6** (3.3.x is below the supported floor). Boot 4 renamed `spring-boot-starter-web` → `spring-boot-starter-webmvc` and introduced per-starter `*-test` modules. Architecture.md may still reference the old names.
- **JDK 21 (Temurin 21.0.11) installed locally via winget** (`EclipseAdoptium.Temurin.21.JDK`). Local `./mvnw verify` works; CI uses `actions/setup-java@v4` for the same.
- **2026-05-29:** Spring Boot 4 reshuffled package layouts. `AutoConfigureMockMvc` is now `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. Jackson `databind`/`core` moved to groupId `tools.jackson.core` (Jackson 3.x); `jackson-annotations` stays at `com.fasterxml.jackson.core`. Per-starter `*-test` modules don't bring everything the umbrella `spring-boot-starter-test` used to — design tests to not depend on Jackson when avoidable.
- **2026-05-29:** Bundled Steps 7–10 into one PR. Violates CLAUDE.md "one step at a time" but user explicitly asked because they form one coherent slice (the 202 Accepted pattern needs endpoint + queue + WS together to demonstrate anything).
- **2026-05-29:** Spring AI GA line (1.0.x) is Boot-3 only — does not contain `spring-ai-starter-model-google-genai`. Bumped to Spring AI **2.0.0-M8** (milestone). Property namespace is `spring.ai.google.genai.*` not `spring.ai.google.*` (Architecture.md is stale on this). User's `.env` uses `Gemini_API_Key`; app reads `GOOGLE_AI_API_KEY`. Spring Boot doesn't auto-load `.env` — needs shell-sourcing or `spring-dotenv` dep to actually inject the key at runtime.
- **2026-05-29:** Stacking Step 12 onto branch `feat/phase2-spring-ai-config` instead of opening a PR for Step 11 alone. User's choice — Parser agent + config are logically coupled and reviewed together.
- **2026-05-29:** Continued stacking Steps 13 + 14 onto `feat/phase2-spring-ai-config`. Branch now carries Steps 11–14; one PR will land all of Phase 2's agent work together. Step 15 (orchestrator) will likely stack too, then PR the whole phase.
- **2026-05-30:** Step 15 completed on `master` working tree. Local Linux Maven is now preferred as `mvn verify`; Maven uses Java 21 via system config. Mockito tests on Fedora/Temurin Java 21 require Surefire to launch with Mockito as `-javaagent` instead of self-attaching.
- **2026-05-30 — 🎉 FIRST SUCCESSFUL END-TO-END RUN.** A 1-entity OpenAPI spec → pipeline reached `COMPLETE`: parsed (1 entity / 5 endpoints) → generated 9 files → validator compiled them clean on **pass 1, zero corrections**. Output is idiomatic Spring Boot CRUD (JPA entity + Lombok, JpaRepository, paginated `@Transactional` service, `ResponseEntity` controller, global exception handler). 23/23 unit tests green throughout. Reaching this took five integration fixes (below).
- **2026-05-30 — Fix 1 (Lombok validation):** Lombok added at **compile** scope (not provided/optional) so the in-process `ValidatorAgent` javac resolves `@Data`/`@Builder` at runtime. Compiler flag `-proc:none` → `-proc:full` so Lombok's processor runs. In-process Lombok compilation works on Temurin 21 with no extra `--add-opens`. Lombok is in the fat jar (compile scope) → Railway unaffected. New `ValidatorAgentTest` compiles a Lombok entity + a consumer using the generated builder/getters.
- **2026-05-30 — Fix 2 (Gemma reasoning output — the big one):** `gemma-4-31b-it` is a **reasoning model**: thinking trace and answer come back as **separate `Generation`s** (thought first, answer last). Spring AI M8's `.entity()`/`.content()` read only the first → returned the thought → parse failed. Gemma **cannot** disable thinking (`thinkingBudget=0` → HTTP 400) and ignores `includeThoughts`. Fix: `agents/StructuredOutput.java` reads the full `ChatResponse`, walks generations **back-to-front**, returns the first that parses via `BeanOutputConverter`. Agents call `.chatResponse()` (not `.entity()`) and append `converter.getFormat()` themselves. **Kept Spring AI + kept thinking.** New `StructuredOutputTest`.
- **2026-05-30 — Fix 3 (token cap):** `AiConfig` sets `ChatClient` default options `maxOutputTokens(32768)` (model's max; old implicit ~8192 truncated reasoning+answer) and `responseMimeType("application/json")`. Model/temperature still from `application.yml` (option merging preserves them).
- **2026-05-30 — Fix 4 (the killer bug — missing JPA deps):** Validator compiles against the backend's **own** classpath. Generator emits JPA CRUD (`jakarta.persistence`, `org.springframework.data.*`, `@Transactional`) but the pom had **no Spring Data JPA** (dropped in the Boot 4 migration; CLAUDE.md Step 6 originally listed it + H2). Result: 22 *phantom* compile errors on correct code → reflection loop could never converge. Added `spring-boot-starter-data-jpa` + `com.h2database:h2` (runtime; satisfies datasource auto-config so the backend still boots). Validation then passed pass 1.
- **2026-05-30 — Fix 5 (observability):** `AgentOrchestrator` logs pipeline progress — parsed counts, generated file list, **per-pass compile errors**, terminal status. Previously the reflection loop was a black box (errors only broadcast as a count, persisted to `job.error` only on final failure).
- **2026-05-30 — Generator prompt hardened** (`prompts/code-generator.st`) after reviewing the first output: entities use `@Getter/@Setter` not `@Data` (avoids equals/hashCode over the mutable `@Id`); read-only service methods `@Transactional(readOnly = true)`; no unused imports; no redundant `@Repository`. Refinements, not breakage — the first output already met every architecture standard.
- **2026-05-30 — Full Petstore at 32K (tested):** Parsing the full Swagger Petstore **succeeded** (6 entities, 19 endpoints). But the single generation call for ~34 files **never returned** — sat in GENERATING >16 min with no truncation error or terminal state (poll gave up at 20 min). Confirms two things at once: the single-shot generator does not scale past a few entities, and there is no LLM read-timeout to abort a stalled/oversized call. See ARCHITECTURE.md "Part 3.5: Production Hardening" for the planned fixes.
- **2026-05-30 — NEXT (optimizations, agreed, not yet implemented):**
  1. **Chunked per-entity generation** — one LLM call per entity, shared files once, merge then validate the whole set, correct per-entity. Keeps every call inside the token budget regardless of spec size.
  2. **LLM read/connect timeout** on the chat client so a hung call fails fast and frees the worker.
  Pipeline is proven; these are optimizations. See also FUTURE_FEATURES.md for larger roadmap ideas (pom.xml generation, workflow/event-driven generation beyond CRUD).
- **2026-05-30 — AGREED ROADMAP (revised — deployability is now #1):**
  0. **🚨 BACKEND DEPLOYABILITY — TOPMOST PRIORITY (existential).** Verify the pipeline runs as a *deployed artifact*, not just under `mvn spring-boot:run`. See the dedicated risk note below. Rationale (user, emphatic): "I do not want to build the best spec→code pipeline and have it only live on my system." This goes before chunking because if the in-process validator can't run from a packaged jar / on Railway, the architecture itself must change — which affects everything downstream.
  1. **Chunking** — re-architect generation (design below).
  2. **MFE + GitHub Actions deploy** — build the React frontends + live GitHub Pages deploy (Phase 3–5).
  3. **F1 + F3** — generate pom.xml + config + unit tests; upgrade validator to a real Maven build as the ground-truth signal.
  4. **Architect F2** — contract-first event-driven / multi-service generation.
  5. **North star** — requirements-convergence + code-as-truth (treat the running system as source of truth; AI helps it evolve). See FUTURE_FEATURES.md.
  Guiding frame: every step is the same reflection-loop engine pointed at a **richer ground-truth signal** (compiler → real build + tests → topology → the running system).
- **2026-05-30 — 🚨 TOPMOST-PRIORITY RISK: backend deployment is undesigned AND today's architecture has a likely-fatal deploy gap.** Status of deployment in the repo: only a *destination* is named ("Railway free tier" in CLAUDE.md / AGENTS.md / ARCHITECTURE.md Part 5 — which is just a cost table). There is **no Dockerfile, no railway.json/nixpacks.toml/Procfile, no backend deploy workflow** (`ci.yml` only builds+tests). Frontends have a GitHub Pages deploy; the backend has nothing.
  - **RISK A — in-process compiler needs a JDK, not a JRE.** `ValidatorAgent` uses `ToolProvider.getSystemJavaCompiler()`, which returns **null on a JRE**. Most PaaS Java buildpacks run on a JRE → every job would fail with "No system Java compiler available." The runtime image MUST ship a full JDK (e.g., `eclipse-temurin:21-jdk`).
  - **RISK B (highest) — Spring Boot fat-jar classpath.** The validator compiles against `System.getProperty("java.class.path")`. Under `mvn spring-boot:run` (how ALL of today's testing was done) that's an *exploded* classpath, so it sees Spring/JPA/Lombok. But as a deployed `java -jar app.jar`, `java.class.path` is **only the launcher jar** — deps live in nested `BOOT-INF/lib/*.jar` the in-process compiler **cannot see**. So generated CRUD that compiled clean in dev would fail in prod with the exact "package does not exist" errors we just fixed. **This has never been tested from the packaged jar.**
  - **FIRST ACTION next session (reproduce before designing a fix):** `cd backend && JAVA_HOME=…temurin-21 mvn -DskipTests package`, run the **fat jar** (`java -jar target/code-forger-backend-*.jar`) with `GOOGLE_AI_API_KEY` exported, submit the mini Widget spec, and watch validation. If it fails with missing-symbol/compiler-null errors, RISK A/B are confirmed.
  - **Likely fixes to evaluate:** (1) run "exploded" — extract the jar (Spring Boot layered jars) and launch via a real classpath dir the compiler can read; (2) build the compiler `-classpath` explicitly from `BOOT-INF/lib` at runtime instead of `java.class.path`; (3) a pathing jar; (4) ship a JDK base image + Dockerfile regardless. NOTE: this strongly motivates FUTURE_FEATURES F1 (move validation to a real Maven build in an isolated sandbox) — but that needs Maven + a warm local repo in the container, heavy for free tier. Decide deliberately.
  - **Secondary deploy concerns (also undesigned):** free-tier RAM (Hibernate + in-process compiler + LLM is heavy — possible OOM); idle-sleep/redeploy wipes the in-memory `JobQueue` (no persistence); `GOOGLE_AI_API_KEY` must be a platform secret (we only use `spring-dotenv`/shell-sourcing locally); CORS for the MFE origins (Step 23, not done); the planned LLM read-timeout matters more in a constrained container.
- **2026-05-30 — CHUNKING DESIGN (next session, not yet implemented):** Keep it encapsulated inside `CodeGeneratorAgent` — `generate()`/`correct()` keep their signatures, so the orchestrator + reflection loop barely change.
  - `generate(ApiSchema)`: one LLM call **per entity** producing just its 5 files (Entity/DTO/Repository/Service/Controller) from that entity + `endpointsFor(entity)` (filter via `Endpoint.entity`); shared files (Application, GlobalExceptionHandler, ErrorResponse, ResourceNotFoundException) generated once; merge all into one `GeneratedCode`; validate the **whole merged set**.
  - `correct(previous, errors)`: group `CompileError`s by file, re-generate the affected **entity slice** with its errors as context, patch back into `previous` (bounds correction size too).
  - Files to touch: rewrite `prompts/code-generator.st` for ONE entity; new `prompts/code-generator-shared.st`; smaller-input `code-generator-correction.st`; loop+merge in `CodeGeneratorAgent`; rework `CodeGeneratorAgentTest` (mock returns per-entity slices); optional per-entity progress broadcast for the MFE stream.
  - **Agreed defaults:** shared files = deterministic **template** (not an LLM call); correction granularity = **per entity slice**; entity generation = **sequential** for v1 (parallelize later, mind the ~15 RPM Gemma limit).
  - Acceptance test: re-run the full Swagger Petstore and confirm it reaches COMPLETE without blowing the token budget.
