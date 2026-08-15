# Next

> Read this first, every session. Rewrite it at the end of every session.
> The next action must be specific enough to start typing within 60 seconds of reading it.
> Bad: "work on the worker." Good: "add jitter to `RetryScheduler.nextAttemptAt` — the
> test at `RetrySchedulerTest:47` is written and currently failing."

**Last session:** 2026-08-15 — week 2 S1: `V1__initial_schema.sql`, the four JPA entities +
repositories, and `SchemaMappingIT`. **Week 2 S1 is done.**

**Where things stand:** On branch `week-02-schema`, three commits ahead of `main`, tree clean,
`./mvnw verify` green with 5 tests. Not merged — the convention is one branch per roadmap week
and S2 is still ahead, so merge to `main` when S2 lands.

V1 creates `endpoint`, `event`, `delivery`, `delivery_attempt` with PKs, FKs and
`unique (tenant_id, idempotency_key)`. No performance indexes, per `DESIGN.md` §4 — but note
five indexes exist anyway, because a UNIQUE constraint *is* an index. Entities are proven
against the migration by `ddl-auto=validate` at context startup, so a drift fails the whole
suite, not one test.

**Next action:**
Week 2 S2 — `POST /v1/events`. Bigger than a single sitting; do it in this order, committing
at each step:

1. **The failing test first.** New `EventIngestIT` alongside `SchemaMappingIT` (same
   `@Import(TestcontainersConfiguration.class)` + `@SpringBootTest` + `@AutoConfigureMockMvc`
   setup that `HealthEndpointIT` already uses). Assert `POST /v1/events` returns **202** with
   an `event_id` body, and that one `event` row plus one `delivery` row per matching active
   endpoint exist afterwards. It should fail with 404 before any controller exists.
2. **API-key auth filter.** Static per-tenant bearer token (`DESIGN.md` §3), resolving to the
   `tenant_id` that every query is scoped by. Decide where the tenant→key mapping lives — a
   config property is fine for v1 and there is deliberately no tenant table.
3. **Ingest + fan-out in one transaction.** This is the point of `DECISIONS.md` #1: persisting
   the `event` and its `delivery` rows is a single commit, so there is no dual-write to
   diverge. Invariant I1 says durable *before* the 202 returns.
4. **Fan-out matching.** Deliver to endpoints where `state = 'active'` **and**
   (`event_types is null` **or** the event's type is in `event_types`). Null means all types;
   an empty array would mean none. `Endpoint.eventTypes` already carries that distinction.

**Blocked on:** nothing.

**Decide before week 3 (found by `/code-review`, deliberately not fixed):**

- **`unique (delivery_id, attempt_no)` on `delivery_attempt` collides with replay.** This is
  the important one, and it's yours to call — it's delivery-state-machine territory. §5 has
  `dead --replay--> pending` on the *same* row, so a replayed delivery either resets
  `attempt_count` to 0 and then violates this constraint on its next attempt, or keeps
  `attempt_count = 8` and goes straight back to `dead` without a request. Options: drop the
  constraint (back to `DESIGN.md` §4 as written); add a `replay_generation` column to the key;
  or make replay insert a fresh `delivery` row. The constraint was added beyond §4 to make a
  double-write of attempt 3 a violation rather than a silent duplicate — that value is real,
  but so is the collision.
- **`delivery.updated_at` never updates.** Set at construction, `not null default now()` fires
  only on INSERT, and there is no `@UpdateTimestamp` or trigger — so every week-3 state
  transition leaves it at creation time. Recommendation: a **DB trigger**, not
  `@UpdateTimestamp`, because the claim query is native SQL and would bypass the JPA callback
  entirely. Land it in the same migration as the claim query.
- **FK child columns are unindexed.** Postgres does not auto-index them, so the RESTRICT check
  on `delete from endpoint` seq-scans `delivery`, and a CASCADE from `event` seq-scans
  `delivery` then `delivery_attempt`. Low practical impact while endpoints are soft-deleted
  and nothing is pruned, but these three are justified by constraints that exist *today*
  rather than by a week-3 guess — which is a real exception to the "no indexes yet" rule.
- **`on delete cascade` from `event` contradicts the audit story.** `delivery_attempt` is
  documented as never deleted, and `endpoint_id` uses RESTRICT to protect exactly that, but a
  retention job doing `delete from event where created_at < …` (`DESIGN.md` §10) would erase
  the evidence behind I5. The two FKs encode opposite policies for the same data.

**Parked / noticed but not doing:**
- `DeliveryState.valueOf` throws from inside Hibernate result-set processing on an unknown
  label. During a rolling deploy the CHECK swap lands first and old instances die on the new
  state — so the rule is deploy code before migration, which should be written down.
- `GenerationType.IDENTITY` on `delivery_attempt` disables Hibernate JDBC insert batching (the
  id is only known post-INSERT). It's the highest-write table in the system and this will show
  in the week-5 load test. `SEQUENCE` with a pooled optimizer keeps the same `bigserial` column
  and restores batching. Also: the three UUID PKs use random v4, which is the exact index
  fragmentation `delivery_attempt` avoided on purpose — `@UuidGenerator(style = TIME)` gives
  UUIDv7 with no schema change.
- No `check ((state = 'in_flight') = (locked_at is not null))` on `delivery`, and no
  `check (event_types is null or cardinality(event_types) > 0)` on `endpoint`. Both invariants
  are currently documented in three places and enforced in none.
- `management.endpoint.health.show-details=always` leaks the absolute filesystem path, free
  disk, and DB details on an unauthenticated `/health`. Change to `when-authorized` by week 14.
- `README.md` still carries three `TODO` blocks: the curl example (unblocked once S2 lands),
  the architecture diagram, and the failure-modes section (both week 14).
- Spring Boot 4 moved a lot of packages. Most tutorials are 3.x and won't compile. Grep the
  jars on the classpath rather than searching the web — `DECISIONS.md` #8 lists what moved.
- A Homebrew `postgresql@14` owns `localhost:5432` on this machine, which is why compose
  uses 5433.
