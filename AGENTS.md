# AGENTS.md — Code Forger

This file is the single source of truth for Codex.
Read this file at the start of every session before doing anything.
Read PROGRESS.md immediately after to know exactly where to resume.

---

## What We Are Building

Code Forger is a multi-agent AI system with:
- 3 separate React MFE apps deployed on GitHub Pages
  using Webpack Module Federation
- 1 Spring Boot backend deployed on Hugging Face Spaces
- A 3-agent AI pipeline (Parser -> Code Generator -> Validator)
  using Spring AI + Gemma 4 31B via Google AI Studio
- Real-time WebSocket streaming of agent status
- Self-correction reflection loop in the Validator agent

Full technical details are in ARCHITECTURE.md.
Read it if you need context on any decision.

---

## Non-Negotiable Ground Rules

1. **One block at a time.** Build one logical unit, then STOP.
2. **Explain before and after.** Before writing code, say what
   you are about to build and why. After, explain what it does,
   what pattern it demonstrates, and what comes next.
3. **Never proceed without confirmation.** After each step, wait
   for "continue" or a question. Do not auto-proceed.
4. **Always name the file.** State the exact file path before
   creating or editing it.
5. **Explain every architectural decision.** If you make a choice
   (e.g. why ConcurrentLinkedQueue vs RabbitMQ), explain the
   tradeoff.
6. **Update PROGRESS.md after every completed step.** This is
   mandatory. Mark the step done, add notes, update next step.
7. **Read PROGRESS.md at session start.** Always. Without
   exception. This is how you know where we left off.

---

## Session Start Protocol

Every time a new Codex session begins:

```text
Step 1: Read this file (AGENTS.md)
Step 2: Read PROGRESS.md
Step 3: Read ARCHITECTURE.md if context is needed
Step 4: State out loud:
  - What has been completed
  - What the next step is
  - Any blockers or questions before proceeding
Step 5: Wait for confirmation to begin
```

---

## Repositories

We are building 4 separate repositories.
Each phase below specifies which repo is active.

| Repo | Purpose | Deployment |
|---|---|---|
| code-forger-backend | Spring Boot API + Agents | Hugging Face Spaces |
| code-forger-shell | MFE Host/Shell | GitHub Pages |
| code-forger-agent-console | MFE 1 - Terminal UI | GitHub Pages |
| code-forger-code-vault | MFE 2 - Code Display | GitHub Pages |

---

## LLM Configuration

- **Primary model:** Gemma 4 31B (`gemma-4-31b-it`)
  via Google AI Studio free tier
- **Lightweight tasks:** Gemma 4 E4B (`gemma-4-e4b-it`)
- **API:** Google AI Studio (same free tier as Gemini)
- **Spring AI starter:** `spring-ai-google-ai-gemini-spring-boot-starter`

---

## Local Tooling Notes

- On Linux, prefer running Maven directly with `mvn verify` from
  `backend/`. The local Maven wrapper may be unusable if Windows/VS Code
  rewrites `backend/mvnw` with CRLF line endings.
- Java 21 is the project target. If `mvn -version` reports Java 25 on
  Fedora, check `/etc/java/maven.conf` or set `JAVA_HOME` explicitly:
  `export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk`.
- Expected local verification command:
  `cd backend && JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk mvn verify`.

---

## Full Build Plan

### PHASE 0 — Backend Repo Setup
**Active repo: code-forger-backend**

- [ ] Step 1 — Monorepo folder structure + reasoning
- [ ] Step 2 — Git init, .gitignore, GitHub remote setup ✓ (done separately)
- [ ] Step 3 — GitHub Actions CI workflow (build + test on PR)
- [ ] Step 4 — Branch protection setup instructions (UI walkthrough)
- [ ] Step 5 — Commitlint + Husky conventional commits setup

### PHASE 1 — Spring Boot Foundation
**Active repo: code-forger-backend**

- [ ] Step 6 — Spring Initializr config + project creation
        Dependencies: Spring Web, WebSocket, Spring AI Google,
        Lombok, Spring Data JPA, H2 (dev)
- [ ] Step 7 — Package structure with reasoning for each package
- [ ] Step 8 — WebSocket configuration (STOMP + SockJS)
- [ ] Step 9 — REST controller with 202 Accepted + Job ID pattern
- [ ] Step 10 — In-memory job queue + job status model

### PHASE 2 — Spring AI + Agent Pipeline
**Active repo: code-forger-backend**

- [x] Step 11 — Google AI / Gemma 4 dependency config +
         application.yml + AiConfig.java
- [x] Step 12 — Agent 1: Parser agent
         (prompt engineering explained in depth)
- [x] Step 13 — Agent 2: Code Generator agent
         (prompt engineering explained in depth)
- [x] Step 14 — Agent 3: Validator agent
         (sandboxed Java compilation + reflection loop)
- [x] Step 15 — Orchestrator wiring all 3 agents +
         WebSocket status broadcasting throughout
- [x] Step 15.5 — Hugging Face Spaces Deployment & Security
- [x] Step 15.6 — Chunked Per-Entity Generation & Rate Limiting

### PHASE 3 — Shell Application
**Active repo: code-forger-shell (new repo)**

- [ ] Step 16 — New repo setup + Webpack Module Federation
         shell/host configuration
- [ ] Step 17 — Runtime remote loading config +
         GitHub Actions deploy to GitHub Pages

### PHASE 4 — Agent Console MFE
**Active repo: code-forger-agent-console (new repo)**

- [ ] Step 18 — New repo setup + MFE webpack config +
         WebSocket connection to backend
- [ ] Step 19 — Live terminal UI component with
         real-time status streaming

### PHASE 5 — Code Vault MFE
**Active repo: code-forger-code-vault (new repo)**

- [ ] Step 20 — New repo setup + MFE webpack config +
         syntax highlighted code display
- [ ] Step 21 — ZIP download functionality (JSZip)
- [ ] Step 22 — Backend polling for generated code by Job ID

### PHASE 6 — Integration & Polish
**Active repos: all**

- [ ] Step 23 — End-to-end wiring + CORS config +
         environment variables
- [ ] Step 24 — Error handling (failed jobs, network errors,
         compile failures after 3 retries)
- [ ] Step 25 — README.md + demo script + talking points

---

## How to Update PROGRESS.md

After completing any step, update PROGRESS.md like this:

```markdown
## ✅ Completed Steps

### Step 3 — GitHub Actions CI workflow
- Status: DONE
- Files created: .github/workflows/ci.yml
- Notes: Uses actions/setup-java@v4 with Java 21,
  runs mvn verify on PR to master
- Completed: 2026-05-10

## 🔄 Current Step

### Step 4 — Branch protection setup
- Status: IN PROGRESS
- What to do: Walk through GitHub UI settings

## ⏳ Next Up
Step 5 — Commitlint + Husky
```

---

## Architecture Reference (quick lookup)

| Concept | Location in code |
|---|---|
| 202 pattern | GeneratorController.java |
| WebSocket config | WebSocketConfig.java |
| Status broadcasting | StatusBroadcaster.java |
| Agent 1 | ParserAgent.java |
| Agent 2 | CodeGeneratorAgent.java |
| Agent 3 + reflection loop | ValidatorAgent.java |
| Pipeline orchestration | AgentOrchestrator.java |
| MFE shell config | code-forger-shell/webpack.config.js |
| MFE remote exposure | code-forger-agent-console/webpack.config.js |
| GitHub Pages deploy | .github/workflows/deploy.yml (each FE repo) |
