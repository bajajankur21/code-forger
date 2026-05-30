# FUTURE_FEATURES.md — Code Forger

Roadmap of larger capabilities beyond the current OpenAPI → Spring Boot CRUD
pipeline. These are *features*, not the near-term optimizations (chunked
generation, LLM read-timeout) — those live in ARCHITECTURE.md "Part 3.5:
Production Hardening" and Progress.md.

Status legend: 💡 idea · 🔬 exploring · 🚧 building · ✅ done

---

## Feature 1 — `pom.xml` generation → a truly runnable project  💡

**Today:** the pipeline emits `.java` files only. The output is correct source
but not a buildable project — there's no `pom.xml`, no `application.yml`. A user
can't `mvn spring-boot:run` what they download.

**Idea:** have the LLM also generate the project's build + config so the ZIP is a
complete, runnable Spring Boot app:

- `pom.xml` — correct `spring-boot-starter-parent`, only the starters the
  generated code actually uses (web, data-jpa, validation, …), Lombok, a dev
  database (H2), Java version.
- `application.yml` — datasource, JPA/Hibernate, server port.
- Optionally `README.md` with run instructions and `mvnw` wrapper.

**Bigger win — close the loop with a real build in the sandbox.** Right now the
`ValidatorAgent` compiles with in-process `javac` against the *backend's* own
classpath, which is why the backend has to carry every dependency the generated
code might use (the Fix-4 coupling). If the generator produces a `pom.xml`, the
validator could instead run a **real, isolated Maven build** (`mvn -o compile`,
or `dependency:resolve` + compile) inside the temp sandbox:

- Dependency resolution becomes correct and self-contained — the generated pom
  declares what it needs; the backend no longer has to mirror it.
- The validation signal gets stronger: it verifies the project *as it will ship*,
  not just that the source compiles against an unrelated classpath.
- The self-correction loop can now also fix **build/dependency** errors, not just
  Java syntax/type errors.

**Considerations:** a real Maven build is slower and needs a warm local
repository (or an offline/cached one) on the deploy target; sandbox isolation and
timeouts matter even more; first-run dependency downloads must be bounded.

---

## Feature 2 — Beyond CRUD: workflow / event-driven system generation  🔬

**Motivation:** Spring Boot's real value isn't CRUD — it's the *systems* it
orchestrates: messaging, async workflows, scheduling, integration. An OpenAPI
spec only describes a request/response surface; it can't express "when an order
is placed, publish to RabbitMQ; a listener reserves inventory; on success emit a
Kafka event the shipping service consumes." That's where the interesting
engineering — and the interesting demo — lives.

**Idea — accept a design document, not just an API spec.** Add a second input
mode: a natural-language **LLDD / README.md** (low-level design doc) describing
components, data flows, and messaging topology. A design-aware parser extracts an
internal *system model* (not just entities/endpoints): components, message
channels, producers/consumers, triggers, and the workflow between them. The
generator then emits the wiring, not just controllers:

- **RabbitMQ** — `@RabbitListener` consumers, `RabbitTemplate` publishers, queue
  / exchange / binding config.
- **Kafka** — `@KafkaListener` consumers, `KafkaTemplate` producers, topic config.
- **Async & scheduling** — `@Async` services, `@Scheduled` jobs, `@EventListener`
  / `ApplicationEventPublisher` for in-process events.
- **Workflow / orchestration** — a saga or state-machine skeleton coordinating the
  steps described in the design doc.
- The CRUD layer becomes one *component type* among many, not the whole output.

**Why it's compelling:** it reframes Code Forger from "API scaffolder" to
"design-doc → working distributed system." It's a much stronger showcase of both
agentic AI (reasoning over an ambiguous design doc) and Spring Boot (its real
integration surface). Code Forger generating a workflow-based system that looks
like *Code Forger itself* (queue + workers + status streaming) is a great north
star.

**Open questions to frame before building:**
- How structured must the input LLDD be? Free-form prose vs. a light template
  (components, messages, flows) that keeps the parser tractable.
- What's the internal *system model* schema the parser emits and the generator
  consumes? (The CRUD `ApiSchema` is the v1; this needs a richer sibling.)
- How does the validator verify event-driven code? Compilation is necessary but
  not sufficient — wiring correctness (a publisher's topic matches a listener's)
  may need static checks or a lightweight integration harness.
- Scope control: a design doc can imply an unbounded system. Need guardrails on
  what one generation run will produce.

---

## Feature 3 — Unit test generation  💡

**Motivation:** generated code that compiles is good; generated code that comes
with its own passing tests is *trustworthy*. A fourth agent (or a generation
mode) produces idiomatic tests alongside the implementation, so every download
ships with a green test suite that documents intended behaviour.

**What to generate (per entity / component):**

- **Service tests** — JUnit 5 + Mockito; mock the repository, verify business
  logic, the not-found path (`ResourceNotFoundException`), and entity↔DTO mapping.
- **Controller tests** — `@WebMvcTest` + `MockMvc`; assert status codes, JSON
  bodies, validation (`400` on bad input), and that the service is invoked.
- **Repository tests** — `@DataJpaTest` against in-memory H2 for custom queries.
- **(Event-driven, ties to Feature 2)** — listener/publisher tests with embedded
  broker or `@MockBean` test doubles.

**Close the loop — run the tests in the sandbox (ties to Feature 1).** Once the
validator can do a real Maven build, it can run `mvn test`. The reflection loop
then expands from "does it compile?" to "do its tests pass?" — a far stronger
correctness signal. A failing test becomes feedback the generator must fix,
exactly like a compile error today.

**Considerations:**
- Running tests is slower and needs the sandbox to bring up H2 / embedded brokers
  — budget time and isolation accordingly.
- Guard against the model writing tautological/trivial tests; the prompt should
  demand meaningful assertions and the key edge cases (not-found, validation).
- Test generation roughly doubles output volume → leans hard on the planned
  chunked generation (tests can be generated per entity, alongside its slice).

---

## Notes

- These are **post-MVP**. The current priority is the optimizations in
  ARCHITECTURE.md Part 3.5 (chunked generation + LLM timeout) so the existing
  CRUD pipeline scales reliably.
- The three features reinforce each other: a generated **`pom.xml`** (F1) enables
  a real Maven build, which lets the sandbox **run generated tests** (F3), and the
  same build/validation machinery extends to **event-driven systems** (F2). The
  shared enabler is moving validation from in-process `javac` to a real,
  isolated, per-project build.
