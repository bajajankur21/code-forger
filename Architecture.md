# Code Forger — Architecture Document

## What is Code Forger?

Code Forger is a multi-agent AI system that accepts an OpenAPI/Swagger
documentation URL as input and generates production-quality Spring Boot
CRUD code as output. It demonstrates enterprise-grade frontend
architecture (Micro Frontends), asynchronous backend design (202
Accepted pattern), real-time communication (WebSockets), and a
multi-agent AI pipeline with a self-correction reflection loop.

**Primary purpose:** Learning vehicle for agentic AI, Spring AI,
Module Federation, and modern architectural patterns.

---

## System Overview

```
User pastes OpenAPI URL into Agent Console
              ↓
Shell App loads Agent Console MFE at runtime
              ↓
Agent Console POSTs URL to Spring Boot backend
              ↓
Backend returns 202 Accepted + Job ID immediately
              ↓
Background worker runs 3-agent pipeline
              ↓
WebSocket streams live status to Agent Console terminal
              ↓
Code Vault MFE polls backend for completed code
              ↓
User sees generated Java files with syntax highlighting
              ↓
User downloads ZIP archive
```

---

## Repository Structure

Three separate frontend repos + one backend repo.
All frontend repos deploy independently to GitHub Pages.

```
GitHub Repositories:
├── code-forger-shell              → GitHub Pages (Shell/Host)
├── code-forger-agent-console      → GitHub Pages (MFE 1)
├── code-forger-code-vault         → GitHub Pages (MFE 2)
└── code-forger-backend            → Hugging Face Spaces (Docker SDK)
```

---

## Part 1: Frontend Architecture

### Why Micro Frontends?

Standard enterprise pattern for large teams — each MFE is owned
by a separate team, deployed independently, and composed at runtime.
This project demonstrates that pattern at a small scale using
Webpack Module Federation.

### The Three Applications

#### 1. Shell Application (Host)
- Empty React container
- Manages top-level routing and navigation
- Dynamically loads remote MFEs at runtime via Module Federation
- Has no knowledge of MFE internals — just knows their URLs
- Deployed to: `https://{username}.github.io/code-forger-shell`

#### 2. Agent Console MFE
- Input field for OpenAPI/Swagger documentation URL
- Submit button triggers job creation via REST POST
- Live terminal window connected via WebSocket
- Displays real-time agent status stream:
  `"Fetching spec..."  →  "Parsing endpoints..."  →
   "Writing PaymentController.java..."  →  "Validating..."`
- Shows Job ID for reference
- Deployed to: `https://{username}.github.io/code-forger-agent-console`

#### 3. Code Vault MFE
- Polls backend for job completion using Job ID
- Displays generated Java files with syntax highlighting
  (using Prism.js or Highlight.js)
- File tree navigation for multiple generated files
- Download as ZIP button (uses JSZip)
- Deployed to: `https://{username}.github.io/code-forger-code-vault`

### Module Federation Configuration

Each MFE repo exposes its root component as a remote:

```javascript
// code-forger-agent-console/webpack.config.js
new ModuleFederationPlugin({
  name: 'agentConsole',
  filename: 'remoteEntry.js',
  exposes: {
    './AgentConsole': './src/AgentConsole',
  },
})

// code-forger-code-vault/webpack.config.js
new ModuleFederationPlugin({
  name: 'codeVault',
  filename: 'remoteEntry.js',
  exposes: {
    './CodeVault': './src/CodeVault',
  },
})
```

Shell loads remotes via live GitHub Pages URLs:

```javascript
// code-forger-shell/webpack.config.js
new ModuleFederationPlugin({
  name: 'shell',
  remotes: {
    agentConsole: 'agentConsole@https://{username}.github.io/
      code-forger-agent-console/remoteEntry.js',
    codeVault: 'codeVault@https://{username}.github.io/
      code-forger-code-vault/remoteEntry.js',
  },
})
```

### GitHub Actions Deploy (each frontend repo)

```yaml
name: Deploy to GitHub Pages
on:
  push:
    branches: [master]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: npm ci && npm run build
      - uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./dist
```

---

## Part 2: Backend Architecture

### Technology Stack

- **Framework:** Spring Boot 3.x
- **AI Layer:** Spring AI with Google AI starter
- **LLM:** Gemma 4 31B via Google AI Studio free tier
- **Real-time:** Spring WebSocket (STOMP over SockJS)
- **Queue:** In-memory queue (ConcurrentLinkedQueue)
- **Deployment:** Hugging Face Spaces (Free CPU Basic)
- **Language:** Java 21

### Package Structure

```
com.codeforger/
├── api/
│   └── GeneratorController.java     ← REST endpoint
├── websocket/
│   ├── WebSocketConfig.java         ← STOMP configuration
│   └── StatusBroadcaster.java       ← pushes updates to frontend
├── queue/
│   └── JobQueue.java                ← in-memory job store
├── orchestrator/
│   └── AgentOrchestrator.java       ← coordinates agent pipeline
├── agents/
│   ├── ParserAgent.java             ← Agent 1
│   ├── CodeGeneratorAgent.java      ← Agent 2
│   └── ValidatorAgent.java          ← Agent 3
├── model/
│   ├── GenerationJob.java           ← job entity
│   ├── ApiSchema.java               ← parsed spec output
│   └── GeneratedCode.java           ← final output
└── config/
    └── AiConfig.java                ← Spring AI + Gemma config
```

### The 202 Accepted Pattern

Why: AI generation takes 15-60 seconds. Standard HTTP would time out.

```
POST /api/generate
Body: { "specUrl": "https://..." }

Response: HTTP 202 Accepted
Body: { "jobId": "uuid-abc-123", "status": "QUEUED" }

GET /api/jobs/{jobId}
Response: { "status": "PROCESSING" | "COMPLETE" | "FAILED",
            "files": [...] }   ← populated when COMPLETE
```

### WebSocket Message Flow

```
Frontend subscribes to: /topic/jobs/{jobId}

Backend broadcasts messages via SimpMessagingTemplate:
{
  "jobId": "uuid-abc-123",
  "phase": "PARSING",
  "message": "Extracting 12 endpoints from spec...",
  "timestamp": "2026-05-01T10:23:45"
}

Message phases:
  QUEUED → PARSING → GENERATING → VALIDATING →
  CORRECTING (if validation fails) → COMPLETE | FAILED
```

### Spring AI Configuration

```yaml
# application.yml
spring:
  ai:
    google:
      api-key: ${GOOGLE_AI_API_KEY}
      chat:
        options:
          model: gemma-4-31b-it
          temperature: 0.2      # low temp for deterministic code
          max-tokens: 32768     # model's max output; reasoning trace + JSON answer share this budget
```

---

## Part 3: Multi-Agent Pipeline

### Agent 1 — The Parser

**Role:** API analyst. Understands the spec, outputs clean schema.
**Input:** OpenAPI/Swagger URL (JSON or YAML)
**Output:** Internal `ApiSchema` JSON object

**What it does:**
- Fetches the spec from the URL
- Extracts: entities, endpoints, HTTP methods,
  request/response shapes, authentication patterns
- Does NOT write any Java code
- Outputs a structured schema the Code Generator can consume

**System prompt pattern:**
```
You are an API specification analyst.
Analyse the provided OpenAPI specification and extract a clean,
structured JSON schema. Output ONLY valid JSON, no explanation.

Schema format:
{
  "entities": [...],
  "endpoints": [...],
  "authentication": "...",
  "basePackage": "..."
}
```

### Agent 2 — The Code Generator

**Role:** Senior Java Developer. Writes the actual .java files.
**Input:** `ApiSchema` from Agent 1 (+ error feedback from Agent 3
  on retry)
**Output:** Map of filename → Java source code string

**What it generates (Per Entity Slice):**
- `{Entity}Controller.java` — REST controller with full CRUD
- `{Entity}Service.java` — service layer with business logic
- `{Entity}Repository.java` — Spring Data JPA repository
- `{Entity}DTO.java` — request/response DTOs
- `{Entity}.java` — JPA entity with Lombok annotations

**Deterministic Shared Files (Java Templates):**
- `{Application}.java`, `GlobalExceptionHandler.java`, `ErrorResponse.java`, `ResourceNotFoundException.java` are generated programmatically via Java templates to save tokens and ensure consistency.

**Throttling:**
- Implements a proactive **15 RPM (4s interval) throttler** to ensure compliance with Google AI free tier rate limits.

### Agent 3 — The Validator (Reflection Loop)

**Role:** Code reviewer. Compiles code, reports errors.
**Input:** Generated Java source strings from Agent 2
**Output:** Compilation result (pass/fail + error details)

**How it works:**
```
1. Writes generated .java strings to temp directory
2. Invokes javax.tools.JavaCompiler programmatically
3. Captures compilation errors with line numbers
4. If errors exist → formats error report → returns to Agent 2
5. Agent 2 receives: original code + error + instruction to fix
6. Loop continues until clean compile OR 3 retries exhausted
7. On 3rd failure → marks job FAILED with error detail
```

**The reflection loop:**
```
Agent 2 output
    ↓
Agent 3 compiles
    ↓
Pass? → Complete
    ↓
Fail? → "You generated this code:
         [code]
         It failed with this error:
         [error]
         Fix only the failing lines. Preserve all other logic."
    ↓
Agent 2 corrects → Agent 3 compiles again
(max 3 iterations)
```

### Orchestrator Flow

```java
// AgentOrchestrator.java (simplified)
public void process(GenerationJob job) {
  broadcast(job.getId(), "Fetching and parsing API spec...");
  ApiSchema schema = parserAgent.parse(job.getSpecUrl());

  broadcast(job.getId(), "Generating Spring Boot code...");
  Map<String, String> files = codeGeneratorAgent.generate(schema);

  broadcast(job.getId(), "Validating generated code...");
  for (int attempt = 1; attempt <= 3; attempt++) {
    ValidationResult result = validatorAgent.validate(files);
    if (result.isSuccess()) break;
    broadcast(job.getId(), "Self-correcting (attempt " + attempt + ")...");
    files = codeGeneratorAgent.correct(files, result.getErrors());
  }

  job.setFiles(files);
  job.setStatus(COMPLETE);
  broadcast(job.getId(), "Done. " + files.size() + " files generated.");
}
```

---

## Part 3.5: Production Hardening & Optimizations

Notes from the first working end-to-end run (2026-05-30). The pipeline is
proven on small specs; these items make it robust and scalable.

### Reasoning-model output handling (implemented)

`gemma-4-31b-it` is a **reasoning model**. Each response contains the thinking
trace and the final answer as **separate parts**, which Spring AI surfaces as
separate `Generation`s (thought first, answer last). Spring AI's `.entity()`
reads only the first generation, so it returns the *thought* and parsing fails.
Thinking cannot be disabled on this model (`thinkingBudget=0` → HTTP 400) and
`includeThoughts=false` is ignored.

Resolution: agents call `.chatResponse()` and pass the result to
`StructuredOutput.parse(...)`, which walks generations **back-to-front** and
returns the first that parses via `BeanOutputConverter`. This keeps Spring AI
and keeps reasoning on (quality). Note the live property namespace is
`spring.ai.google.genai.*` (the snippet above is illustrative).

### Output token budget & chunked generation (implemented)

`max-tokens` (32768, the model's ceiling) is the budget for the **reasoning
trace + the JSON answer combined**. Generating all files for a multi-entity
spec in one call overruns it.

**Solution — generate per entity:**

- One LLM call per entity producing just its slice (`Entity`, `DTO`,
  `Repository`, `Service`, `Controller`).
- Shared, entity-independent files (`Application`, `GlobalExceptionHandler`,
  `ErrorResponse`, `ResourceNotFoundException`) generated programmatically via Java templates.
- Merge all slices into one `GeneratedCode`, then validate the **whole set
  together** so cross-file references resolve.
- Correction operates on the whole set (v1) with smaller input/output for stability.
- Proactive 4s throttling ensures compliance with 15 RPM limits.

### LLM call timeouts (PLANNED)

The chat client has **no read/connect timeout**. A stalled or oversized call
blocks the single orchestrator worker thread indefinitely (observed: a full
Petstore generation hung >16 min with no way to abort). Add explicit
connect/read timeouts to the underlying HTTP client so a hung call fails fast,
surfaces as a job error, and frees the worker for the next job.

### Validator classpath (implemented)

The `ValidatorAgent` compiles generated code in-process against the **backend's
own** classpath (`System.getProperty("java.class.path")`). It must therefore
carry every dependency the generated code targets — `spring-boot-starter-data-jpa`
(JPA, Spring Data, `@Transactional`), Lombok (compile scope so its annotation
processor is present at runtime), plus H2 so the backend still boots with the JPA
starter. A missing dependency shows up as phantom "cannot find symbol" errors that
the reflection loop can never fix.

---

## Part 4: Cost Strategy

| Task | Model | Est. Cost/Generation |
|---|---|---|
| Parsing spec | Gemma 4 E4B | ~$0.001 |
| Code generation | Gemma 4 31B | ~$0.02-0.05 |
| Validation feedback | Gemma 4 E4B | ~$0.001 |
| Self-correction (per retry) | Gemma 4 31B | ~$0.02 |

**Estimated cost per generation: ~$0.03-0.10**
Google AI Studio free tier covers this comfortably for development.

---

## Part 5: Deployment & Security

### Infrastructure

| Component | Platform | Cost |
|---|---|---|
| Shell App | GitHub Pages | Free |
| Agent Console MFE | GitHub Pages | Free |
| Code Vault MFE | GitHub Pages | Free |
| Spring Boot Backend | Hugging Face Spaces | Free |
| LLM (Gemma 4 31B) | Google AI Studio | Free tier |

**Total infrastructure cost: $0/month**

### Backend Containerization (The Fat-Jar Classpath Solution)

Deploying the Spring Boot backend on a PaaS (like Hugging Face Spaces) presents two challenges for the in-process `JavaCompiler`:
1. It requires a JDK, not a JRE.
2. It requires an explicit `-classpath` containing all dependencies. When run as `java -jar app.jar`, `System.getProperty("java.class.path")` only contains the launcher JAR, causing the compiler to fail to find Spring/JPA symbols.

**Solution:** A multi-stage `Dockerfile` that targets `eclipse-temurin:21-jdk`. It extracts the Spring Boot layered jar using `jarmode=layertools` and boots the application with an exploded classpath:
`java -cp "application/BOOT-INF/classes:application/BOOT-INF/lib/*" ...`
This expands the dependencies directly onto `java.class.path`, allowing the `ValidatorAgent` to function identically to local `mvn spring-boot:run` execution.

**Hugging Face Specifics:**
- **Metadata:** A root `README.md` with YAML metadata is required to trigger the Docker SDK.
- **Port:** The container must listen on port `7860`.
- **User:** The container runs as UID `1000`.
- **Memory:** CPU Basic tier provides **16GB RAM**, enabling a large `-Xmx` for stable AI generation and compilation.

### Continuous Deployment (CD)

Backend deployments are automated via GitHub Actions (`cd.yml`). On push to `master` (and after `ci.yml` passes), the workflow pushes the codebase to the Hugging Face Space repository, which triggers an automatic build and rollout of the new Docker container.

### Authentication & CORS

Because the MFE frontends are statically hosted on GitHub Pages, we cannot bake an API key into them securely. To prevent unauthorized usage of the Google AI Studio free tier:
- **Passcode Auth:** A shared secret (`APP_SECRET_PASSCODE`) is defined in the backend environment.
- **REST:** A Spring `OncePerRequestFilter` intercepts `/api/**` requests and requires an `X-API-Key` or `Authorization` header matching the passcode.
- **WebSocket:** A STOMP `ChannelInterceptor` reads the passcode from native connection headers and disconnects unauthenticated WebSocket sessions.
- **Frontend:** The Agent Console MFE provides a simple UI for the user to enter this passcode once, storing it in `localStorage` for subsequent calls.
- **CORS:** A global `WebMvcConfigurer` and updated `StompEndpointRegistry` explicitly allow traffic from `https://*.github.io`.

---

## Key Architectural Patterns Demonstrated

| Pattern | Where |
|---|---|
| Micro Frontend with Module Federation | All 3 frontend repos |
| 202 Accepted async pattern | GeneratorController |
| WebSocket real-time streaming | StatusBroadcaster |
| Multi-agent pipeline | Agent 1 → 2 → 3 |
| Reflection / self-correction loop | Validator → Generator |
| Event-driven background processing | JobQueue + Orchestrator |
| DTO separation | All generated code |
| Domain-Driven package structure | Backend package layout |