# CLAUDE.md

## What this project is

A self-hostable webhook delivery service (a mini Svix/Hookdeck). Senders POST events to
it; it guarantees delivery to registered receiver endpoints with retries, signing,
isolation, and an observable delivery log.

This is a **portfolio and learning project**, not a product. That changes the rules below.

## The prime directive

I must be able to defend every design decision and explain every non-trivial line in a
technical interview. Optimise for my understanding, not for lines of code shipped.

Concretely, work falls into three buckets. Ask which one applies if it isn't obvious:

| Bucket | What | How we work |
|---|---|---|
| **You drive** | Scaffolding, migrations, DTOs, config, test fixtures, React CRUD screens, CI YAML | Just write it. I'll skim. |
| **I drive, you review** | Retry scheduling, per-endpoint fairness, ordering vs. head-of-line blocking, circuit breaker state machine, HMAC verification, the delivery state machine | I write the first version. You critique it, name the failure modes I missed, and suggest tests. **Do not write these for me unprompted.** |
| **You teach** | Anything I ask "why" about | Explain with the trade-off and the alternative I didn't pick. Don't jump to code. |

If I ask you to implement something in the "I drive" bucket, push back once and ask if I
want to attempt it first. If I confirm, go ahead.

## Session constraints

- I work ~6 hours/week in two sessions: one ~2h weeknight, one ~4h weekend.
- **Every session must end committed and on green.** Never leave a broken build.
- Never start a change in the last 30 minutes that can't be finished or reverted cleanly.
- At session end, update `NEXT.md`, and `BROKE.md` if anything failed interestingly.
- Open with `/session-start` and close with `/session-end` — the two skills in
  `.claude/skills/` implement the rules above, including the questions to ask me at the end.

## Working agreements

- **Plan before large changes.** Use plan mode; I approve the plan before you touch files.
- **Tests first for anything in the "I drive" bucket.** The failing test is the spec.
- Small commits, conventional style (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`).
- One branch per roadmap week: `week-07-hmac-signing`. Merge to `main` when green.
- If you're about to add a dependency, say why and what it replaces. Default is no.
- If you disagree with something in `DESIGN.md`, say so — but don't silently deviate.
  Changing a decision means updating `DECISIONS.md` in the same commit.

## Stack

- Java 21 (virtual threads), Spring Boot 4.x, Maven (see `DECISIONS.md` #8 — 3.x was
  dropped from Initializr as out of OSS support)
- PostgreSQL 16 + Flyway. **Postgres is the queue** (`SELECT … FOR UPDATE SKIP LOCKED`).
  No Kafka, no RabbitMQ, no Redis unless a specific need is demonstrated — see
  `DECISIONS.md` #1.
- JDK `HttpClient` for outbound delivery, explicit connect + read timeouts always
- JUnit 5 + Testcontainers (real Postgres in tests, never H2)
- Micrometer → Prometheus → Grafana, all via docker-compose
- k6 for load tests
- Frontend: React + TypeScript + Vite, TanStack Query, Tailwind, recharts, SSE for live tail
- Deploy: single VM or Fly.io. Boring on purpose.

## Commands

```bash
docker compose up -d          # postgres, prometheus, grafana
./mvnw spring-boot:run        # api + worker
./mvnw test                   # unit + Testcontainers integration
./mvnw verify                 # full check before commit
cd web && npm run dev         # frontend
k6 run load/flaky-receiver.js # load test against the chaos receiver
```

Narrower test runs — the ones worth typing during a session:

```bash
./mvnw test -Dtest=RetrySchedulerTest                # one class
./mvnw test -Dtest=RetrySchedulerTest#appliesJitter  # one method
./mvnw test -Dtest='*IT'                             # integration tests only
```

Testcontainers needs a running Docker daemon. If `./mvnw test` fails before any test
reports, check Docker is up before assuming the build is broken — a stopped daemon is not
a red build and is not a reason to revert.

## Hard scope boundaries

Out of scope for v1. If I ask for these, remind me they're out and ask if I'm
deliberately changing scope:

- Multi-region, HA, or horizontal scaling beyond "more worker instances"
- Self-serve signup, billing, teams, RBAC
- A transformation/filtering DSL for payloads
- Kafka or any external broker
- Global ordering guarantees (per-endpoint ordering only, and only if explicitly built)

## Files that carry state between sessions

- `NEXT.md` — the literal next action. Read this first, every session.
- `DESIGN.md` — the design doc. The source of truth for schema and semantics.
- `ROADMAP.md` — 14-week plan with checkboxes.
- `DECISIONS.md` — one entry per non-obvious choice, with what was rejected.
- `BROKE.md` — dated log of what failed, what I observed, what I changed. This becomes
  the best section of the README. Treat entries here as valuable, never delete them.
- `LESSONS.md` — what *I* misunderstood, and concepts I had to ask about. Distinct from
  `BROKE.md`: that file is what the **system** did and wants numbers; this one is what
  **I** believed and wants the correction. Revision material for interviews. Never delete.
  I can't reliably self-report these — you noticed them, so **you propose the entries**.
- `README.md` — mostly written, but carries two `TODO (week 14)` blocks that are real
  deliverables: the architecture diagram, and the failure-modes section built from
  `BROKE.md`. Don't let them get lost.
