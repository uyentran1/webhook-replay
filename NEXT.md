# Next

> Read this first, every session. Rewrite it at the end of every session.
> The next action must be specific enough to start typing within 60 seconds of reading it.
> Bad: "work on the worker." Good: "add jitter to `RetryScheduler.nextAttemptAt` — the
> test at `RetrySchedulerTest:47` is written and currently failing."

**Last session:** 2026-08-08 — settled `DESIGN.md` §6 and §7, then built the skeleton.
**Phase 0 is done.**

**Where things stand:** Spring Boot 4.1.0 / Java 21 skeleton on `main`, tree clean, build
green. Postgres 16 via `docker compose up -d` on **host port 5433** (not 5432 — see
`BROKE.md`). Flyway wired with zero migrations so far. `/health` returns 200 with `db: UP`.
Two tests pass, both hitting a real Postgres through Testcontainers.

Design is settled through §8. §7b and §7c are marked **PREDICTION** — deliberately written
before any measurement so week 5 can prove them wrong. Do not quietly "fix" them before then;
the delta between prediction and measurement is the point.

**Next action:**
Week 2 S1 — the first migration. Create
`src/main/resources/db/migration/V1__initial_schema.sql` with the four tables exactly as
specified in `DESIGN.md` §4: `endpoint`, `event`, `delivery`, `delivery_attempt`. Primary keys,
foreign keys, and the `unique (tenant_id, idempotency_key)` constraint on `event`.

**No indexes yet.** `DESIGN.md` §4 says add them when you can justify them, and the
justification is the claim query, which doesn't exist until week 3. Resist.

Verify by running `./mvnw verify` — `HealthEndpointIT` already boots Flyway against a real
Postgres, so a broken migration fails the build immediately. That test is your migration
harness; you don't need to write a new one for this step.

**Then:** JPA entities + repositories for the four tables, with `ddl-auto=validate` proving the
entities match the migration rather than the other way round.

**Blocked on:** nothing.

**Parked / noticed but not doing:**
- `management.endpoint.health.show-details=always` leaks the absolute filesystem path, free
  disk, and DB details on an unauthenticated `/health`. Useful while building; wrong once this
  is deployed. Change to `when-authorized` in week 14 (deploy) at the latest.
- `README.md` still carries three `TODO` blocks: the curl example (needs the API, week 2–3),
  the architecture diagram, and the failure-modes section (both week 14). `CLAUDE.md` calls the
  last two real deliverables — don't let them get lost.
- Spring Boot 4 moved a lot of packages. Most tutorials are 3.x and won't compile. When an
  import won't resolve, grep the jars on the classpath rather than searching the web —
  `DECISIONS.md` #8 has the list of what moved.
- A Homebrew `postgresql@14` is running on this machine and owns `localhost:5432`. Unrelated to
  this project, but it's why compose uses 5433.
