# What broke

Dated log of things that failed, what I observed, and what I changed. Written at the time,
because reconstructing it in month four is impossible.

This file becomes the best section of the README, and it's the source of every story worth
telling about this project in an interview. Numbers beat adjectives — "p99 delivery latency
went from 31s to 240ms" is the whole point; "it was slow" is worth nothing.

Include failures caused by my own misunderstanding. Those are the most interesting ones and
the ones I'll be tempted to leave out.

Format: `## YYYY-MM-DD — Title` / **Symptom** / **Observed** / **Cause** / **Fix** /
**Learned**

---

## 2026-08-08 — App talked to the wrong Postgres for 15 minutes

**Symptom:** `./mvnw spring-boot:run` died on startup with Flyway reporting
`FATAL: role "webhook" does not exist`, while `docker compose ps` showed the container
**healthy** and its own healthcheck (`pg_isready -U webhook -d webhook_replay`) passing.

**Observed:** `lsof -nP -iTCP:5432 -sTCP:LISTEN` showed two listeners — a Homebrew
`postgresql@14` on `127.0.0.1:5432` and `[::1]:5432`, and OrbStack on `*:5432`. Testcontainers
tests were green the whole time, which is what made it confusing: `./mvnw verify` passed and
only `spring-boot:run` failed.

**Cause:** Docker binds the wildcard address; a locally-installed Postgres binds loopback
specifically. **A more specific bind wins**, so `localhost:5432` resolved to the Homebrew
Postgres 14, which has no `webhook` role and no `webhook_replay` database. The compose
container was listening the whole time and never received a connection. Testcontainers was
unaffected because it assigns a random high port per run.

**Fix:** Moved the compose host port to **5433** and defaulted `DB_PORT` to match, rather than
stopping the Homebrew service — the project should not depend on what else is installed on the
machine.

**Learned:** A healthy container proves the container is healthy; it proves nothing about what
the client connected to. The diagnostic that mattered was "who is listening on this port",
not "is my container up". Also: a green test suite and a broken `run` is a *signal*, not a
contradiction — it localises the fault to configuration that tests bypass, which here was the
datasource URL that Testcontainers overrides via `@ServiceConnection`.

---

## 2026-08-15 — The dev database was broken for a whole session and nothing noticed

**Symptom:** `./mvnw spring-boot:run` failed at startup with Flyway reporting
`PSQLException: ERROR: relation "endpoint" already exists`, on a branch where
`./mvnw verify` had just passed 16 tests.

**Observed:** In the compose database, all four tables present, `flyway_schema_history`
present but with **0 rows**, and **0 rows in all four tables**. So Flyway believed no
migration had ever been applied and tried to create tables that were already there. The
breakage had been latent for **one full session** — week 2 S1 ended green and committed, and
nothing between then and now ran the application.

**Cause:** V1 was edited in place last session, which is correct per the rule in `LESSONS.md`
("can I drop every database this has been applied to? If yes, edit it") — but the rule was
only half-applied. Editing an applied migration breaks Flyway's checksum; clearing the
history row makes that error go away, and the tables were left behind. The database was then
in a state no code path could reconcile.

**Fix:** Dropped the four empty tables and let Flyway apply V1 cleanly. Deliberately *not*
`docker compose down -v` — the volume was not the problem, and the narrower action is the one
that fits the fault.

**Learned:** The test suite structurally cannot detect this. Testcontainers starts a fresh
Postgres per run, so `./mvnw verify` never touches the compose database — which is exactly
what makes it a good test harness and exactly why it is blind here. **This is the second
entry in this file with the same shape: green tests, broken `spring-boot:run`.** The
2026-08-08 entry was the first. In both cases the fault lived in configuration or state that
Testcontainers overrides.

*The ritual gap this exposes:* `/session-start` runs `docker compose up -d` and `./mvnw
verify`, and **neither starts the app**. A compose database can therefore stay broken
indefinitely. Worth adding a `spring-boot:run` smoke check — start it, curl `/health`, kill
it — to session-start, since that is the only thing that exercises the real datasource.

---

<!-- Example of the shape and level of detail to aim for. Delete once you have real entries.

## 2026-09-12 — One hanging endpoint starved every other delivery

**Symptom:** Under k6 load with 20 endpoints, only 1 of which hangs for 30s, overall
delivery throughput collapsed and healthy endpoints saw multi-minute delays.

**Observed:** Throughput fell from ~1,850/s to ~40/s. p99 delivery latency for *healthy*
endpoints went from 180ms to 34s. All 8 worker threads were blocked on the one bad
endpoint within ~15s of it going bad.

**Cause:** The claim query took the oldest N pending deliveries regardless of endpoint.
The hanging endpoint accumulated the largest backlog, so it won an ever-growing share of
each claim batch — a feedback loop. Read timeout was 30s, so each blocked worker was out
of commission for the full window.

**Fix:** (what you actually did — cap in-flight per endpoint / round-robin claim / etc.)

**Learned:** The failure isn't "one slow endpoint uses one worker," it's that backlog size
and claim share are coupled, so a failing endpoint's share grows monotonically. I'd been
thinking of fairness as a nice-to-have; it's load-bearing. Also: my read timeout was doing
nothing useful at 30s — the bad endpoint was well outside any real receiver's latency.

-->

<!-- Template

## YYYY-MM-DD — Title

**Symptom:**

**Observed:**

**Cause:**

**Fix:**

**Learned:**

-->
