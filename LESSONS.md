# Lessons

Things I got wrong, and things I had to ask about. Written down because a misconception you
correct once and never revisit comes back in an interview.

Two kinds of entry:

- **Corrected** — I believed something specific and it was wrong. These are the valuable ones.
- **Learned** — I didn't know a concept at all and asked. Less sharp, still worth revision.

Grouped by theme rather than by date, so related entries sit together for revision; each entry
carries its own date. New entries go under the matching heading, or start a new one.

Do not delete entries. An old misconception is evidence of how far this has come, and the ones
that feel embarrassing are exactly the ones that stuck.

---

## Distributed systems and delivery semantics

**2026-08-08 — Corrected: N attempts means N−1 waits.**
I wrote 13 backoff values for a 12-attempt policy. Waits sit *between* attempts, so 12 attempts
has 11 gaps — the classic fencepost error (12 fence posts, 11 spans between them). This is why
the retry schedule in `DESIGN.md` was claiming ~24h of coverage when the real figure was 68
minutes. *Sanity check for next time: a doubling series sums to just under twice its last term.
If the total isn't ≈ 2× the biggest entry, I've miscounted.*

**2026-08-08 — Corrected: the request timeout and the retry window are different clocks.**
I proposed that the request timeout should be "at least equal to the worst-case retry", i.e.
27.6 hours. The retry window is how long we keep *coming back*; the request timeout is how long
we wait for *one* HTTP response. A 27.6h request timeout means a worker blocked on one socket
for over a day, which violates I4 and makes the retry schedule unreachable. **The request
timeout is the smallest clock in the system**, and everything else is built on top of it.

**2026-08-08 — Corrected: an endpoint is a URL, not a customer.**
I assumed "per-endpoint" meant "per receiving company". An endpoint is one registered URL; one
customer can register several with different `event_types` filters.

**2026-08-08 — Learned: ordering scope and concurrency are the same knob.**
Serialisation is required *within* a partition, so achievable concurrency equals the number of
partitions. Global ordering → 1 in-flight request service-wide. Per-endpoint → 1 per endpoint,
which caps that endpoint at `1 ÷ latency` deliveries/sec (10/s at a 100ms round trip) as a
*steady-state* cost on a healthy endpoint. Finer partition = more concurrency = smaller blast
radius. This is why `ordering_key` beats per-endpoint FIFO, and why nobody ships global ordering.

**2026-08-08 — Learned: Little's Law, `L = λW`.**
Got this one right unprompted: 200 events/sec × 0.1s latency = 20 concurrent requests needed to
keep up. The follow-through is the part to remember — if strict ordering allows concurrency 1,
you serve 10/s against 200/s demand and the backlog grows 190/s forever. It doesn't degrade,
it never converges.

**2026-08-08 — Learned: the two legs, and why the split *is* the product.**
Leg 1 is sender → us (`POST /v1/events`, 202 in ~10ms). Leg 2 is us → receiver (the outbound
webhook, retried for ~28h). Everything in `DESIGN.md` §6 governs leg 2 only. The whole value of
the service is severing leg 1 from leg 2, so the sender's latency stops being hostage to the
receiver's.

**2026-08-08 — Learned: ordered dispatch ≠ ordered processing.**
Even sending strictly in order only guarantees the receiver *received* them in order. Their
handler acks fast and processes async (which is what we tell them to do), so their own job queue
reorders it anyway. You can never offer more than ordered dispatch.

## Java, Maven and the build

**2026-08-08 — Learned: what a classpath is.**
The ordered list of jars and directories the JVM searches to find a class or resource at runtime.
First match wins. 12 declared dependencies here expand to 142 jars transitively. Two payoffs:
`classpath:db/migration` works because Maven copies `src/main/resources/` into `target/classes/`,
which is on the classpath; and `<scope>` decides *which* classpath a dependency joins, which is
why `postgresql` at `runtime` scope can't be imported from `src/main/java`. Inspect with
`./mvnw dependency:tree`.

**2026-08-08 — Learned: `mvn` vs `./mvnw`.**
`mvn` is Maven installed on the machine, whatever version that happens to be. `./mvnw` is the
Maven Wrapper — a script committed to the repo that pins an exact version
(`.mvn/wrapper/maven-wrapper.properties`, currently 3.9.16) and downloads it on first use. It
means a fresh clone builds with only a JDK, and CI needs no Maven install step. At work `mvn`
works because someone installed it; on this machine Maven isn't installed at all.

**2026-08-08 — Learned: Flyway vs Liquibase.**
Same job, mostly renaming. `V1__initial_schema.sql` ≈ a changeset; `flyway_schema_history` ≈
`DATABASECHANGELOG`. The real difference is that Liquibase abstracts DDL across database vendors
while Flyway is SQL-first. That abstraction is worth nothing here — Postgres-only by
`DECISIONS.md` #1, and the schema leans on `jsonb`, `text[]` and `SKIP LOCKED`, which you'd end
up wrapping in raw `<sql>` blocks anyway. Gotchas: two underscores after the version, and never
edit an applied migration (checksums) — fix forward with `V2__`.

**2026-08-08 — Corrected: "Skipped writing … No changes found" is success, not failure.**
`dependency:build-classpath` compares against the existing file and skips writing if identical.
The build said `BUILD SUCCESS` and the file was correct all along. *General lesson: read the
build result line, not the most alarming-sounding line.*

## Shell and environment

**2026-08-08 — Learned: what `$PATH` is.**
A colon-separated list of directories the shell searches for an executable when you type a bare
command; first match wins (`which git` shows the winner). Installing a CLI tool *is* putting a
binary somewhere on `$PATH` — which is why `docker` went from "command not found" to working
without me typing it differently. The current directory is deliberately **not** on `$PATH`, for
security, which is why it's `./mvnw` and never `mvnw`. Same shape as the classpath, one level up.

**2026-08-08 — Learned: `/tmp` is an absolute path, and macOS-specific facts about it.**
`/tmp` starts at the filesystem root, not the project. On macOS it's a symlink to `/private/tmp`.
Finder hides it (`open /tmp` to look). It's shared by every app and gets purged — files untouched
for three days are deleted, and it clears on reboot. Fine for throwaway output, wrong for
anything you'd miss.

## Docker

**2026-08-08 — Corrected: the app runs on 8080, not 5433.**
I conflated "runs on" with "connects to". webhook-replay listens on **8080**; it *connects to*
Postgres on 5433. Nothing I own listens on 5433 except Docker's port forwarder.

**2026-08-08 — Learned: port mapping is `hostPort:containerPort`.**
`"5433:5432"` means Postgres listens on 5432 *inside* its container (always — the container has
its own network namespace, nothing to collide with) and Docker forwards host 5433 to it. Both
numbers in OrbStack's UI are correct. **Consequence for week 10:** when Prometheus and Grafana
join the compose file they're containers too, so they'll reach Postgres at `postgres:5432` —
service name and *container* port. Container-to-container traffic never goes via the host mapping.

**2026-08-08 — Learned: `up` / `down` semantics, and the dangerous flag.**
`up -d` creates network, volumes and containers, then starts them detached. `down` stops and
*removes* containers and the network but **keeps named volumes**, so the data survives.
`down -v` deletes the volumes and the database with them — only when a clean slate is wanted.
Compose finds `docker-compose.yml` by searching the current directory and walking up parents, so
anywhere in the project tree works; outside it fails. Run from the root anyway, because the
project name (and thus which containers `down` kills) derives from that directory.
