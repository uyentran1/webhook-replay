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

**2026-08-09 — Learned: the IDE's project model is a separate artifact from Maven's classpath.**
IntelliJ red-lined `@SpringBootApplication` and every `org.springframework` import as "cannot
resolve" while `./mvnw verify` was green. Both were telling the truth: Maven builds its classpath
from `pom.xml` and `~/.m2` and hands `javac` an explicit `-classpath`; IntelliJ builds its own
model at *import* time and never consults Maven for editor analysis. **A red editor with a green
`./mvnw verify` means the IDE model is stale or absent, and is never evidence about the build.**
Root cause: opening the *folder* gives you a generic Java project with an empty classpath — only
opening `pom.xml` itself triggers a Maven import. Installing the Maven plugin doesn't do it, and
"Reload All Maven Projects" is a silent no-op when no pom is registered. *Corollary: for IDE
state, check disk, not the UI.* `.idea/` is hidden by the IDE (2025.x special-cases it out of the
Project view entirely — the **Project Files** scope does *not* reveal it; use the built-in
terminal), by Finder (dotfile), and by `.gitignore` — and on 2025.x the module
model isn't in `.idea/` at all, it's under
`~/Library/Caches/JetBrains/<IDE>/projects/<name>.<hash>/external_build_system/`. The tell that
an import actually ran is `.idea/compiler.xml` naming the module.

**2026-08-15 — Learned: JPA, Hibernate and Spring Data JPA are three different things.**
JPA is a *specification* — annotations and interfaces, no behaviour. Hibernate is the
implementation that does the work. Spring Data JPA sits on top and generates repository
implementations from interface declarations. Worth keeping distinct because the answer to "why
is your claim query native SQL?" depends on it: Spring Data derives queries from method names
and cannot express `FOR UPDATE SKIP LOCKED` or a window function, so week 3's claim loop drops
to `@Query(nativeQuery = true)` while CRUD stays derived. *The defensible line: ORM for the
CRUD, hand-written SQL for the queue.*

**2026-08-15 — Learned: Spring's `DataIntegrityViolationException` is a translation, applied
at the repository proxy boundary.** Surfaced from a failing assertion in `SchemaMappingIT`:
`entityManager.flush()` threw Hibernate's native `ConstraintViolationException` instead.
Hibernate throws vendor-flavoured exceptions; Spring translates them into its own
vendor-neutral hierarchy, but only for calls that pass through a repository proxy. Going
straight to the `EntityManager` goes around the translator. *Consequence: the exception the
week-9 ingest path catches depends on how it triggers the flush. Prefer `saveAndFlush` in
tests, so the test sees what production will.*

**2026-08-15 — Learned: Boot 4 ships Jackson 3, which moved its base package.**
`com.fasterxml.jackson.databind.JsonNode` does not exist; it is
`tools.jackson.databind.JsonNode`. The annotations alone kept the old coordinates, so both
`tools/jackson` 3.1.4 and `com/fasterxml/jackson/jackson-annotations` 2.21 sit on the
classpath together and neither is wrong. *Same lesson as the Boot 4 package moves in
`DECISIONS.md` #8: grep the jar, don't trust the tutorial.*

**2026-08-15 — Learned: `.properties` files are read as ISO-8859-1, not UTF-8.**
Verified in `spring-boot-4.1.0.jar` — `OriginTrackedPropertiesLoader` uses
`StandardCharsets.ISO_8859_1`, following the `java.util.Properties` spec. So a UTF-8 em-dash
(3 bytes) arrives as three characters, which is where the stray `â` in my comments came from.
Harmless in a comment, **silent corruption in a value** — a non-ASCII password or description
would be mangled with no error. `application.properties` is now deliberately pure ASCII.
*Rule: `.properties` is an ASCII format; put anything else in YAML, which is UTF-8.*

## Postgres, schema and migrations

**2026-08-15 — Learned: DDL vs DML, and that Postgres DDL is transactional.**
DDL defines structure (`CREATE`/`ALTER`/`DROP TABLE`, constraints, indexes); DML moves rows
(`SELECT`/`INSERT`/`UPDATE`/`DELETE`). A Flyway migration is a file of DDL. The part that
matters operationally: Postgres wraps DDL in transactions, so a migration that creates three
tables and fails on the fourth rolls all three back — you never hand-repair half a schema.
MySQL does not do this. *A small, real dividend of the Postgres-only decision (`DECISIONS.md` #1).*

**2026-08-15 — Corrected: switching the column to a Postgres `ENUM` would let me delete the
state converters.** It would not. Postgres enum labels are **case-sensitive**, and Hibernate
binds a Java enum by its constant name — `PENDING`, uppercase. A native `ENUM` with lowercase
labels fails exactly as `@Enumerated(STRING)` does, just with a different error message. The
converters do **case translation**; the column type is not what forces them. The knob that
actually removes them is the *label case*, under either column type. *Rule of thumb: before
changing a type to delete a class, name the exact line that becomes unnecessary. If you can't,
the class is doing a different job than you think.* Full reasoning in `DECISIONS.md` #9.

**2026-08-15 — Corrected: "never edit an applied migration, fix forward with `V2__`" applies
everywhere.** It applies to migrations that have **escaped your machine**. The rule exists
because Flyway checksums an applied migration and refuses a mismatch — which only bites where
you cannot drop the database. `V1` on an unmerged branch, applied to a local dev DB and to
throwaway Testcontainers instances, does not qualify: editing it and recreating the schema is
correct, and shipping a `V1`+`V2` pair that adds a column and immediately changes it, before
either ever ran anywhere real, bakes a permanent scar into the schema history for nothing.
*Test to apply: can I drop every database this has been applied to? If yes, edit it.*

**2026-08-15 — Learned: a `UNIQUE` constraint *is* an index.**
`DESIGN.md` §4 says no indexes until a query justifies them, and V1 obeys that — yet it
created five, because Postgres has no way to enforce uniqueness or a primary key other than
building a unique index. *So "no indexes yet" is shorthand for "no indexes chosen for read
performance." Verify what actually exists with `select tablename, indexname from pg_indexes`,
not by reading the migration.*

**2026-08-15 — Learned: what Postgres 16 really costs you on `ENUM`.**
Checked against the running container rather than assumed, after I asserted an outdated
restriction. `ALTER TYPE … ADD VALUE` **does** run inside a transaction — that was lifted in
PG 12 — but the new value cannot be *used* until the transaction commits, so one migration
cannot add a state and backfill rows into it. And there is no `ALTER TYPE … DROP VALUE` in any
version, so removing a state means rebuilding the type and rewriting the column. *Both facts
took 30 seconds to check against a live database, which is faster than being wrong about them.*

**2026-08-15 — Corrected: I had written into `Event.payload`'s javadoc that the stored bytes
must be exactly the bytes the sender sent, so the §8 signature stays valid.** Both halves
were wrong. It is unachievable — `jsonb` normalises on write, verified against the running
PG 16: `'{"b":1, "a":2, "a":3}'::jsonb` returns `{"a": 3, "b": 1}`, keys reordered,
whitespace dropped, duplicate collapsed. And it is unnecessary — a receiver can only verify a
signature against the body it was handed, so the invariant that matters is **the bytes we
sign are the bytes we send**, which is self-consistent regardless of storage. Clinching
argument: week 9 replay *must* re-sign with a fresh timestamp or the receiver rejects it as a
replay attack, so a replayed delivery already carries different signed bytes than the
original — preserving ingest bytes could never have paid off. *Sanity check: before writing
an invariant into a comment, ask which component could actually observe a violation. Nothing
downstream can see the sender's original bytes, so nothing could ever have depended on them.*

## Spring Security and web auth

**2026-08-15 — Learned: authentication and authorisation are two separate steps, and the
gap between them is why my filter never rejects anything.**
`ApiKeyAuthFilter` reads the bearer key, resolves a tenant and puts an `Authentication` in
the `SecurityContext`. That is *all* it does — it makes no access decision. Much later in the
chain, `AuthorizationFilter` compares the rules from `authorizeHttpRequests` against whatever
is in that context, and `ExceptionTranslationFilter` turns a failure into our JSON 401 via
the `AuthenticationEntryPoint`. **Consequence: if the filter threw on a missing header,
`/health` would 401 too, because the filter runs before anything knows the path is public.**
The filter reports; the rules decide. *Rule of thumb: a filter that rejects is a filter that
has hardcoded an authorisation policy in the wrong place.*

**2026-08-15 — Learned: Spring Security is one servlet `Filter`, not many.**
Boot registers a single `FilterChainProxy`; the dozen "filters" live inside it as an ordered
list, which is why `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` is
positioning within that list rather than in Tomcat's. The tenant then reaches the controller
via `@AuthenticationPrincipal`, an argument resolver that reads
`SecurityContextHolder.getContext().getAuthentication().getPrincipal()`. Our principal *is*
the tenant UUID, which is the whole isolation story: a sender cannot name a tenant, so it
cannot name someone else's.

**2026-08-15 — Corrected: I expected adding the security starter to break `/health`. It
didn't, and the reason is the important part.** Boot's
`ManagementWebSecurityAutoConfiguration` ships a chain that permits health and secures
everything else. It is `@ConditionalOnMissingBean(SecurityFilterChain.class)` — so it backs
off the moment you declare your own chain. **Adding the dependency was safe; declaring the
chain is what would have silently closed `/health` to the load balancer.** *General shape:
Boot auto-configuration is not a floor you build on top of, it is a default that disappears
entirely once you supply the same bean. Ask what an auto-config was doing for you before
replacing it.*

**2026-08-15 — Learned: what "signing" a webhook actually means.**
HMAC-SHA256 over `timestamp . body` with the per-endpoint `signing_secret`, sent as a header
alongside an unmodified body. It answers two questions at once: authenticity (only a holder
of the secret could produce it) and integrity (not a byte changed). The timestamp is *inside*
the signed material so a captured request can't be replayed forever, and it must also be sent
as its own header or the receiver can't recompute. Rejected alternatives: plain `SHA256(body)`
proves nothing since anyone can compute it; `SHA256(secret + body)` is broken by
length-extension, which is the attack HMAC exists to prevent; asymmetric signing is genuinely
better for many-receiver setups but costs key distribution, and Stripe and Svix both chose
HMAC. *The receiver-side trap to document in week 7: verify against the **raw** body, because
frameworks that auto-parse JSON destroy the bytes and re-serialising gives a different
signature.*

## Testing

**2026-08-15 — Corrected: my own fan-out suite proved less than it looked like it did.**
Six tests around `EndpointRepository.findMatching`, all green. But every case either used a
null `event_types` filter or asserted that *no* delivery was created — so a
`:type = any(event_types)` expression that never matched anything at all would have left the
entire suite passing. Added `deliversWhenTheTypeIsListedInTheFilter`, the only test that
fails if that expression is wrong. *Sanity check that catches this: for each branch of a
predicate, ask "which test goes red if I delete this branch?" A pile of assert-empty tests is
satisfied by a query that returns nothing, ever.*

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
