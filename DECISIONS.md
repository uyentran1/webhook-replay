# Decisions

One entry per non-obvious choice. Each says what was rejected and why — the rejected
option is the half an interviewer actually probes. Keep entries short. Never delete one;
if a decision reverses, add a new entry that supersedes it.

Format: `## N. Title` / **Date** / **Decision** / **Rejected** / **Why** / **Revisit when**

---

## 1. Postgres as the queue, not Kafka

**Date:** (fill in)

**Decision:** Delivery scheduling uses a Postgres table claimed with
`SELECT … FOR UPDATE SKIP LOCKED`. No external broker.

**Rejected:** Kafka, RabbitMQ, Redis streams, SQS.

**Why:** Three reasons, in order of weight.

1. **No dual-write problem.** Persisting the event and creating its delivery rows happens
   in one transaction. With an external broker, "saved to DB" and "published to broker"
   are two writes that can diverge, and fixing that properly means a transactional outbox
   — which is a Postgres queue plus extra moving parts.
2. **It's sufficient.** `SKIP LOCKED` comfortably handles thousands of deliveries/sec on
   modest hardware, well past what a single-operator deployment needs.
3. **It's defensible.** "I chose the simplest thing that satisfies the invariant, and here
   is the load number at which I'd revisit it" is a stronger interview answer than "I used
   Kafka," which invites the question "why did you need Kafka?"

**Revisit when:** sustained ingest exceeds ~5k events/sec, or fan-out to a single event
exceeds a few thousand endpoints, or multiple independent consumer groups appear.

---

## 2. At-least-once delivery, not exactly-once

**Date:** (fill in)

**Decision:** Deliver at least once. Receivers dedupe on `event_id`.

**Rejected:** Attempting exactly-once.

**Why:** Exactly-once across a network boundary you don't control is not achievable — a
receiver can process a request and then have the response lost, and there's no way to
distinguish that from a receiver that never got it. Pushing idempotency to the receiver
with a stable `event_id` is what every real webhook provider does. The honest version of
this constraint is documented rather than hidden.

**Revisit when:** never. This is a property of the problem.

---

## 3. Java 21 + Spring Boot

**Date:** (fill in)

**Decision:** Java 21, virtual threads for outbound delivery.

**Rejected:** Kotlin (equivalent, less familiar to me), Go (better fit for the concurrency,
but not the stack I'm interviewing on).

**Why:** It's the stack I want to be evaluated on, and the workload — thousands of
concurrent blocking outbound HTTP calls — is a genuinely good fit for virtual threads
rather than a decorative use of them.

**Revisit when:** n/a.

---

## 4. An explicit retry table, not an exponential formula

**Date:** 2026-08-08

**Decision:** `retry_schedule = [5, 300, 1800, 7200, 18000, 36000, 36000]` seconds — 8
attempts (1 + 7 retries), 27h 35m total. Adopted from Svix's shipped default.

**Rejected:** `base 2s × 2^n, cap 1h, 12 attempts`, which is what the design originally said.

**Why:** Working out the actual numbers showed the original parameters did not mean what the
doc claimed. 12 attempts is 11 waits (fencepost), summing to 4094 s = **68 minutes**, not the
"~24h of coverage" written down — off by a factor of 21. Worse, the largest wait (2048 s)
never reaches the 1 h cap, so `min(3600, …)` never binds: the cap was dead configuration that
read as if it were protecting something.

The deeper reason a formula was wrong: a constant multiplier cannot be dense early *and*
sparse late. Real schedules are a ramp that flattens into a plateau. Svix's own table decays
60× → 6× → 4× → 2.5× → 2× → 1× between consecutive waits — it is called "exponential backoff"
in their docs but is not geometric, and Stripe's reported shape matches it through the middle.
Two independent systems converging on the same five intervals is the strongest signal
available in a field with no textbook answer.

~28 h rather than Stripe's 3 days because the retry schedule is not the only mechanism.
It answers "is this receiver having a bad day?" — a receiver that is *gone* is §7c's question
on a 3–5 day timescale, and `dead` is visible and replayable (I3), not lost.

**Revisit when:** week 5 load-test data exists; or if the `dead` rate shows deliveries failing
at attempt 8 that would plausibly have succeeded at attempt 9 or 10.

---

## 5. No jitter

**Date:** 2026-08-08

**Decision:** Fixed schedule, no randomisation. Herd control is delegated to per-endpoint
concurrency limiting (§7b).

**Rejected:** Full jitter (the original design), equal jitter, decorrelated jitter.

**Why:** Jitter has exactly one job — decorrelating clients that would otherwise retry in
lockstep. Deliveries enter the queue at different times, so at any instant they occupy
different positions in the table and are already spread. Jitter would re-solve that.

The case it does not cover: a burst of events for one endpoint arriving together while that
endpoint is down. They fail simultaneously, all sit at attempt 1, and stay synchronised
through the entire table. Jitter would *smear* that cohort but not *bound* it — a recovering
receiver still sees whatever rate the smear produces. A per-endpoint concurrency cap bounds
it directly. Solving this in one place beats half-solving it in two, and it is why §7b is
load-bearing rather than optional.

Note Svix ships a fixed table with no jitter either, and handles load elsewhere.

**Revisit when:** week 5 shows a synchronised cohort overwhelming a recovering receiver in a
way §7b does not fix.

---

## 6. Request timeout 30s, kept deliberately generous

**Date:** 2026-08-08

**Decision:** connect 3 s, request 30 s, delivery lease 60 s. These govern the outbound
delivery leg (us → receiver) only.

**Rejected:** request timeout 10 s.

**Why:** The request timeout is the ceiling on how long one bad endpoint can hold a worker —
a fairness bound (I4), not politeness. 10 s bounds it tighter, but real receivers are
frequently badly built and do synchronous work before acking; cutting them off burns an
attempt *and* causes duplicate processing on the retry, manufacturing the exact problem
at-least-once already imposes on them. 30 s matches Svix, so it is defensible without data —
and today there is no data.

The project-specific reason is stronger: week 5's job is to make the starvation failure
*visible* and record numbers for it. A tight timeout mildens the failure and blurs the
week-6 before/after. Tuning the timeout is not the fix — it trades one starvation rate for
another without bounding pool occupancy. Only §7b does that.

Lease is **derived**, not chosen: it must exceed connect + request + margin or the reaper
re-dispatches in-flight deliveries and creates duplicates.

**Revisit when:** §7b lands in week 6 and bounds per-endpoint occupancy properly — at that
point a shorter timeout costs less. Also if the attempt log shows a meaningful population of
receivers legitimately responding between 10 s and 30 s.

---

## 7. Best-effort ordering, not guaranteed — per-endpoint or otherwise

**Date:** 2026-08-08

**Decision:** Deliveries dispatch in `next_attempt_at` order; parallel workers and retries may
reorder them. Documented as a non-guarantee in §2. Receivers reconcile with a version or by
re-fetching state. An opt-in per-endpoint `ordering_key` is named as the extension point and
deliberately not built.

**Rejected:** Strict per-endpoint FIFO.

**Why:** Ordering scope and concurrency are the same knob — serialisation is required within a
partition, so concurrency equals partition count. Strict per-endpoint ordering caps an endpoint
at `1 ÷ latency` deliveries/sec (10/s at a 100 ms round trip) no matter how many workers exist,
and above that the backlog grows without bound. That is a steady-state cost on a *healthy*
endpoint, which is worse than head-of-line blocking — a cost paid only when something breaks.

Second, we can only offer ordered *dispatch*. §8 tells receivers to ack fast and process async,
so their own job queue reorders whatever we sequenced. A flag named "ordered delivery" would
cost 1× concurrency for a promise that breaks downstream, and receivers would build on it.

Third, the receiver-side fix is strictly better and already half-built: they must dedupe on
`event_id` for at-least-once (I2), so ignoring stale updates is a small marginal ask — and it
survives their async processing, which our ordering cannot. Order-sensitive events are
order-sensitive because they are deltas; versioned state removes the sensitivity.

Both reference implementations agree. Stripe: *"Stripe doesn't guarantee the delivery of events
in the order that they're generated"*, and recommends re-fetching the object. Svix: regular
endpoints are best-effort, strict FIFO is a separate opt-in endpoint type; they state that
almost all senders — Stripe, GitHub, Shopify, Svix — are best-effort. Svix's stated reason for
best-effort is this project's exact mechanism: parallel workers, and retries landing after
newer messages.

Also agrees with the `CLAUDE.md` scope boundary, which permits per-endpoint ordering only if
explicitly built.

**Revisit when:** a concrete user need appears that receiver-side reconciliation genuinely
cannot serve. Build `ordering_key` then, not strict per-endpoint FIFO.

---

## 8. Spring Boot 4.1, not 3.x

**Date:** 2026-08-08

**Decision:** Spring Boot 4.1.0 on Java 21. `CLAUDE.md`'s stack section updated to match.

**Rejected:** Spring Boot 3.x, as originally specified.

**Why:** Initializr no longer offers 3.x — the only options are 4.0.x and 4.1.x — which means
3.x is past OSS support. Pinning it would mean hand-maintaining the parent version, running an
unsupported branch, and answering "why are you on an EOL version?" in the interview this
project exists to serve. Java 21 is unchanged; virtual threads (`spring.threads.virtual.enabled`)
are the reason for it and Boot 4 supports them fine.

Cost, worth knowing before debugging: Boot 4 reorganised starters and packages. `spring-boot-starter-web`
is now `-webmvc`; test support is split into per-module `-test` starters; Testcontainers artifacts
renamed (`testcontainers-postgresql`, not `postgresql`); and annotations moved into per-module
namespaces — `AutoConfigureMockMvc` is now in `org.springframework.boot.webmvc.test.autoconfigure`.
**Most tutorials and Stack Overflow answers will be 3.x and will not compile.** Prefer the jars
on the classpath over search results when an import cannot be resolved.

**Revisit when:** n/a.

---

## 9. States as lowercase `text` + CHECK, not a Postgres ENUM

**Date:** 2026-08-15

**Decision:** `endpoint.state` and `delivery.state` are `text` with a CHECK constraint,
holding lowercase labels (`active`, `in_flight`). The Java enums map through an
`AttributeConverter` rather than `@Enumerated(STRING)`.

**Rejected:** a native Postgres `ENUM` type; and uppercase labels, which would have allowed
`@Enumerated(STRING)` and deleted both converter classes.

**Why:** Two separate questions got asked together, and they have different answers.

*Column type.* Verified against the running PG 16 rather than assumed: `ALTER TYPE … ADD
VALUE` **does** run inside a transaction (that restriction was lifted in PG 12), but the new
value cannot be *used* until that transaction commits — so one migration cannot add a state
and backfill rows into it. And there is no `ALTER TYPE … DROP VALUE` in any version, so
removing or renaming a state means rebuilding the type and rewriting the column. §7c
describes deliveries being *held* while a breaker is open, which makes a sixth delivery state
a plausible week-8 migration; `text` + CHECK keeps that a one-line constraint swap.

*Label case.* The converters were initially blamed on the column type. They are not: Postgres
enum labels are case-sensitive and Hibernate binds a Java enum by its constant name, so a
native `ENUM` with lowercase labels would fail exactly as `@Enumerated(STRING)` does — the
converters do **case translation**, and the column type is not what forces them. Uppercase
labels would have removed them under either column type. Chose idiomatic lowercase SQL and
kept the two converters: the storage format is not the wire format, and the DTO layer owns
the wire format from week 2 S2 onward.

**Revisit when:** the converters start accumulating logic beyond case mapping, or a third
state column appears and the boilerplate becomes three classes rather than two.

---

## 10. Spring Security for API-key auth, not a hand-rolled filter

**Date:** 2026-08-15

**Decision:** Added `spring-boot-starter-security` (+ `-security-test`). One
`SecurityFilterChain` bean: `/health` open, everything else authenticated, CSRF off,
sessions stateless. An `ApiKeyAuthFilter` resolves `Authorization: Bearer <key>` to a tenant
UUID and puts it in the `SecurityContext` as the principal.

**Rejected:** a bare `OncePerRequestFilter` registered on its own, with no security
dependency at all — roughly forty lines using only what was already on the classpath. This
was the recommendation; overruled deliberately.

**Why:** The bare filter is smaller and adds nothing to the dependency tree, which is the
default answer under CLAUDE.md. Two things bought the dependency instead. First, a real
`SecurityContext` means the tenant arrives at controllers through the same mechanism every
Spring codebase uses, rather than a request attribute this project invented — and week 9's
DLQ replay and the week 11 console will both need authorisation decisions that a request
attribute has no vocabulary for. Second, rolling your own auth is the kind of thing that
reads as a warning sign rather than as economy, however correct this particular forty lines
would have been.

The costs were real and are now paid: CSRF had to be switched off (it defends a
browser-attached credential, which a bearer key is not), sessions forced to stateless, the
auto-configured in-memory user excluded so startup stops logging a generated password, and
ERROR dispatches explicitly permitted — without that last one, a 400 from bean validation is
re-authorised on its way to `/error` and surfaces as a 401.

One thing the dependency *took away*, which is the part worth remembering: Boot's
`ManagementWebSecurityAutoConfiguration` had been permitting `/health` for free, and it backs
off the instant a `SecurityFilterChain` bean is declared. Adding the starter did not break
the health check; declaring the chain would have. `ApiKeyAuthIT` pins it.

**v1 shortcut, priced in:** keys are plaintext in configuration, so anyone who can read it can
act as any tenant, and rotation is a redeploy. There is no tenant table because there is no
self-serve signup (§9). Real key storage is hashed at rest with a prefix lookup for the
constant-time compare — the `sk_` prefix is what makes that lookup possible, since you cannot
find a row by hashing the presented key and scanning every stored hash.

**Amended the same day, after `/code-review`:** the dev keys were originally committed to
`application.properties`, which was worse than "plaintext in config" — it was a permanent
backdoor. **Spring Boot *merges* `Map`-typed properties across sources rather than replacing
them**, so an operator supplying production keys externally would have *added* to the map;
`sk_test_alice` would have kept authenticating as tenant `1111…` in production, and no
configuration change could have removed it. Keys now live in `application-local.properties`
(dev only) and in `@SpringBootTest(properties = ...)` (tests); the packaged artifact ships
none. `ApiKeyProperties.apiKeys` carries `@DefaultValue` because "no keys configured" is now
the normal case rather than an error, and without it the binder produces `null` and every
credentialed request 500s instead of returning 401. `NoApiKeysConfiguredIT` pins that.

*The general lesson, worth more than the fix:* a `List` property is replaced by the
highest-precedence source, a `Map` accumulates across all of them. Anything secret in a
committed `Map` is additive and unremovable.

**Revisit when:** tenants need to be created without a restart, or a second kind of principal
appears (an operator for the console is the likely one) and "authenticated" stops being a
single question.

---

<!-- Template — copy for each new decision

## N. Title

**Date:**

**Decision:**

**Rejected:**

**Why:**

**Revisit when:**

-->
