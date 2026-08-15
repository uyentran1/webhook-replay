# Next

> Read this first, every session. Rewrite it at the end of every session.
> The next action must be specific enough to start typing within 60 seconds of reading it.
> Bad: "work on the worker." Good: "add jitter to `RetryScheduler.nextAttemptAt` — the
> test at `RetrySchedulerTest:47` is written and currently failing."

**Last session:** 2026-08-15 — week 2 S2: `POST /v1/events`, Spring Security API-key auth,
fan-out in one transaction. **Week 2 is done.**

**Where things stand:** On branch `week-02-schema`, tree clean, `./mvnw verify` green with
**16 tests**. Verified end-to-end against the real app, not just MockMvc: 401 without a key,
202 with one, and the `delivery` row visible in psql afterwards.

Week 2 is complete, so **this branch should merge to `main`** — that did not happen last
session and is the first thing to do.

**Next action:**

1. **Merge `week-02-schema` to `main`** and branch `week-03-endpoints`. Week 2 S1 and S2 are
   both done; the convention is one branch per roadmap week.
2. **Week 3 S1 — `POST/GET /v1/endpoints`.** Registration returns the signing secret, once.
   Same shape as this session: write `EndpointApiIT` first, then the controller.
   - The `signing_secret` is generated server-side and **shown only at registration**
     (DESIGN.md §3). Decide how it is generated — `SecureRandom`, not `Math.random` or
     `UUID.randomUUID`, and decide the length and the `whsec_` prefix convention.
   - `GET /v1/endpoints` must never return the secret. That is the test worth writing first.
   - Reuse `@AuthenticationPrincipal UUID tenantId` for scoping, exactly as
     `EventIngestController` does — every query filters by tenant.
   - This unblocks the README TODO at the direct-insert snippet, which is already narrowed
     and waiting for it.

**Blocked on:** nothing.

**From `/code-review` at the end of week 2 S2 — seven of eight fixed the same day**, after
manual testing confirmed the two payload bugs. Suite went 16 -> 22 tests. Both new payload
tests were mutation-checked: with the guards replaced by `if (false)`, the object test returns
202 and the NUL test errors, so they pin the fix rather than passing incidentally.

Fixed: dev keys out of the packaged artifact into `application-local.properties`
(`DECISIONS.md` #10 amended); `@DefaultValue` on `ApiKeyProperties.apiKeys`, with
`NoApiKeysConfiguredIT` pinning 401-not-500; escaped NUL and non-object payloads rejected as
400 in `IngestRequest`; `@Component` dropped from `ApiKeyAuthFilter` so it exists only inside
the chain; `createEmptyContext()` instead of mutating the shared `SecurityContext`; README
quickstart reordered so registering a receiver comes first.

**Still open — this one needs a decision, not a fix:**

- **Every timestamp comes from the app clock, but week 3's SQL will use the DB clock.**
  `Delivery` initialises `createdAt`, `updatedAt` and `nextAttemptAt` with `Instant.now()`,
  and Hibernate always includes those columns in the INSERT, so V1's `default now()` never
  fires. The claim query (`next_attempt_at <= now()`) and the 60 s lease reaper will read
  Postgres's clock instead. **Decide before week 3 S2:** either drop the Java initialisers and
  let the DB defaults own these columns, or set them from one injected `Clock`. Skew between
  app host and DB otherwise makes fresh deliveries unclaimable or claimable early, and shifts
  the lease window. Left alone deliberately -- it is delivery-timing semantics and belongs
  with the claim query rather than ahead of it.

**Add to the session ritual (from today's `BROKE.md` entry):** `/session-start` runs
`docker compose up -d` and `./mvnw verify`, and **neither starts the app**. That let a broken
compose database survive an entire session. Add a smoke check — `spring-boot:run`, curl
`/health`, kill it — because Testcontainers structurally cannot catch this.

**Decide before week 4 (still open, carried from last session):**

- **`unique (delivery_id, attempt_no)` on `delivery_attempt` collides with replay.** Still
  yours to call, and now closer: §5 has `dead --replay--> pending` on the *same* row, so a
  replayed delivery either resets `attempt_count` to 0 and violates the constraint on its
  next attempt, or keeps `attempt_count = 8` and goes straight back to `dead` without a
  request. Options: drop the constraint; add a `replay_generation` column to the key; or make
  replay insert a fresh `delivery` row.
- **`delivery.updated_at` never updates.** `not null default now()` fires only on INSERT and
  there is no `@UpdateTimestamp` or trigger, so every week-3 state transition leaves it at
  creation time. Recommendation stands: a **DB trigger**, not `@UpdateTimestamp`, because the
  claim query is native SQL and would bypass the JPA callback. Land it with the claim query.
- **FK child columns are unindexed.** `delete from endpoint` seq-scans `delivery` for the
  RESTRICT check; a CASCADE from `event` seq-scans `delivery` then `delivery_attempt`.
- **`on delete cascade` from `event` contradicts the audit story.** A retention job doing
  `delete from event where created_at < …` (§10) would erase the evidence behind I5, while
  `endpoint_id` uses RESTRICT to protect exactly that.

**Parked / noticed but not doing:**
- **Week 8 will have to change a test.** `EventIngestIT.doesNotDeliverToDisabledOrCircuitOpenEndpoints`
  pins today's behaviour: an event arriving while a breaker is open produces **no delivery row
  at all**. DESIGN.md §7c wants those deliveries *held*. The predicate to revisit is
  `state = 'active'` in `EndpointRepository.findMatching`.
- **`Idempotency-Key` is accepted and ignored.** The column exists and the header is not read.
  Storing it without week 9's dedupe would turn a duplicate POST into a 500 from a constraint
  violation, which is worse. Week 9 owns the header and the 409/replayed-response behaviour.
- No `check ((state = 'in_flight') = (locked_at is not null))` on `delivery`, and no
  `check (event_types is null or cardinality(event_types) > 0)` on `endpoint`.
- `management.endpoint.health.show-details=always` leaks the filesystem path, free disk and DB
  details on an unauthenticated `/health`. Now properly fixable — Security is on the classpath,
  so `when-authorized` finally means something. Week 14.
- `DeliveryState.valueOf` throws from inside Hibernate result-set processing on an unknown
  label, so the rule is deploy code before migration. Should be written down.
- `GenerationType.IDENTITY` on `delivery_attempt` disables Hibernate JDBC insert batching, on
  the highest-write table; this will show in the week-5 load test. `SEQUENCE` with a pooled
  optimizer keeps the same `bigserial` column. The three UUID PKs use random v4 —
  `@UuidGenerator(style = TIME)` gives UUIDv7 with no schema change.
- Boot 4 moved a lot of packages and Jackson 3 moved to `tools.jackson`. Grep the jars on the
  classpath rather than searching the web; most tutorials are 3.x and won't compile.
- `application.properties` is deliberately pure ASCII — Boot reads `.properties` as
  ISO-8859-1, so non-ASCII in a *value* would corrupt silently.
- A Homebrew `postgresql@14` owns `localhost:5432` on this machine, which is why compose
  uses 5433.
