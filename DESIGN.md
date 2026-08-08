# Design: webhook-replay

> Status: **§1–§8 settled** as of 2026-08-08 (week 1). §7b and §7c are marked PREDICTION —
> written before any measurement, deliberately, so the week-5 load test can prove them wrong.
> Grade and revise them in weeks 6 and 8, and record the delta in `BROKE.md`.
> §10 open questions are still open.

## 1. Problem

A company whose product emits events (order paid, build finished, message delivered)
wants to notify its customers' systems. The naive implementation inside a request handler:

```java
order.save();
httpClient.post(customer.webhookUrl, payload);   // <- everything wrong lives here
```

Failure modes this hits, roughly in the order you discover them:

1. Receiver is down or mid-deploy → event lost forever, customer's state is now wrong
2. Receiver is slow → your own request latency is now hostage to theirs
3. Retries without idempotency → receiver processes the same event twice
4. No signature → anyone who learns the URL can forge events
5. One dead receiver retried forever → starves delivery for everyone else
6. No delivery log → "we never got that webhook" is unanswerable

**Users.** The *sender* is the customer: a backend engineer at a platform company. The
*receiver* is the beneficiary — never touches the service, but is the reason every hard
requirement exists.

## 2. Invariants

These are the promises. Everything else is negotiable.

- **I1.** An event accepted with 202 is durably persisted before the response returns.
- **I2.** Every accepted event is attempted at least once per active endpoint (at-least-once).
- **I3.** A delivery is never abandoned silently — it ends in `delivered` or `dead`, and
  `dead` is visible and replayable.
- **I4.** One slow or failing endpoint cannot prevent delivery to unrelated endpoints.
- **I5.** Every attempt is recorded with status, latency, and response snippet.
- **I6.** Every request carries a signature the receiver can verify, with replay protection.

Explicit **non**-guarantees: exactly-once delivery (impossible; receivers dedupe on
`event_id`), and ordering — global *or* per-endpoint. Delivery order is best-effort only;
see §7a.

## 3. API surface

```
POST   /v1/events                  # ingest. Idempotency-Key header. -> 202 {event_id}
GET    /v1/events/{id}             # event + its deliveries
POST   /v1/endpoints               # register receiver. -> {id, signing_secret}
GET    /v1/endpoints
PATCH  /v1/endpoints/{id}          # url, state, event-type filter
DELETE /v1/endpoints/{id}
GET    /v1/deliveries              # filter: endpoint, state, time range
GET    /v1/deliveries/{id}         # includes full attempt timeline
POST   /v1/deliveries/{id}/replay  # manual replay from DLQ
GET    /v1/stream                  # SSE live tail of attempts
```

Auth: static API key per tenant, `Authorization: Bearer`. Deliberately not OAuth.

## 4. Data model

```sql
endpoint(
  id uuid pk, tenant_id uuid, url text, description text,
  signing_secret text, event_types text[] null,
  state text,                    -- active | circuit_open | disabled
  consecutive_failures int, circuit_opened_at timestamptz,
  created_at timestamptz
)

event(
  id uuid pk, tenant_id uuid,
  idempotency_key text,          -- unique per tenant
  type text, payload jsonb, created_at timestamptz,
  unique (tenant_id, idempotency_key)
)

delivery(
  id uuid pk, event_id uuid fk, endpoint_id uuid fk,
  state text,                    -- pending | in_flight | delivered | retrying | dead
  attempt_count int,
  next_attempt_at timestamptz,
  locked_at timestamptz, locked_by text,
  created_at, updated_at
)

delivery_attempt(                -- append-only
  id bigserial pk, delivery_id uuid fk, attempt_no int,
  status_code int null, latency_ms int, response_snippet text,
  error text null, attempted_at timestamptz
)
```

Shape to keep in mind: `event` is what arrived, `delivery` is the fan-out (one event → N
deliveries, one per matching endpoint), `delivery_attempt` is the immutable log. That last
table is what makes the UI interesting instead of CRUD.

Indexes that will matter (add when you can justify them, not before):
`delivery(state, next_attempt_at)` for the claim query, `delivery_attempt(delivery_id)`
for the timeline, `delivery(endpoint_id, created_at desc)` for the log view.

## 5. Delivery state machine

```
          ┌──────────────────────────── retry scheduled ──────────────┐
          v                                                           │
      pending ──claim──> in_flight ──2xx──> delivered                 │
                              │                                       │
                              ├──non-2xx / timeout──> retrying ───────┘
                              │
                              └──attempts exhausted──> dead  ──replay──> pending
```

`in_flight` needs a lease (`locked_at`, `locked_by`) so a worker that dies mid-delivery
doesn't strand the row. A reaper returns leases older than 60 s (derived in §6) to `retrying`.
**Consequence:** a delivery can be attempted twice if the lease expires while the request
is actually still in flight. That's at-least-once working as intended, not a bug — but say
so out loud in the README.

## 6. Retry policy

An explicit table, not a formula. 8 attempts (1 initial + 7 retries), no jitter, total
elapsed **27h 35m**. Waits in seconds, each starting when the preceding attempt concludes:

```
retry_schedule = [5, 300, 1800, 7200, 18000, 36000, 36000]
```

| attempt | wait before it | elapsed since attempt 1 |
|---|---|---|
| 1 | — | 0 |
| 2 | 5 s | 5 s |
| 3 | 5 min | 5 m 5 s |
| 4 | 30 min | 35 m 5 s |
| 5 | 2 h | 2 h 35 m |
| 6 | 5 h | 7 h 35 m |
| 7 | 10 h | 17 h 35 m |
| 8 | 10 h | 27 h 35 m |

Attempt 8 failing → `dead`. Adopted from Svix's shipped default (`config.default.toml`),
which is the reference implementation of this exact problem.

**Why a table rather than `base * 2^n`.** Svix's docs call the above "exponential backoff",
but the multiplier between consecutive waits decays 60× → 6× → 4× → 2.5× → 2× → 1×. It is
not geometric, and neither is Stripe's. A constant multiplier cannot serve both ends of the
range at once: you want dense retries early (a 3-second deploy blip should recover in
seconds) and sparse retries late (hour six of an incident does not need a probe every
minute). Reading the table as three zones shows the intent:

- **5 s** — one fast retry. Catches the receiver that was restarting.
- **5 min → 30 min → 2 h** — the ramp. Catches "someone got paged and is looking at it."
- **5 h → 10 h → 10 h** — the plateau. Nobody is fixing this soon; stop asking. Almost all
  of the 27.6 h lives here.

**Why ~28 h and not 3 days.** The retry schedule is not the only mechanism, and it should
not try to be. It answers "is this receiver having a bad day?" A receiver that is simply
*gone* is a different question on a different timescale, answered by circuit breaking (§7c)
and the fact that `dead` is visible and replayable (I3), not lost. So the question §6
answers is not "how long until I give up" but **"how long is it still worth spending a
request to find out?"** Past ~28 h the DLQ is a cheaper answer than another attempt.

**Why no jitter.** Deliveries enter the queue at different times, so at any instant they sit
at different positions in the table — the schedule's own structure spreads them. Jitter
would re-solve that.

It does *not* cover the case that matters: a burst of N events for one endpoint arriving
together while that endpoint is down. They all fail at once, all sit at attempt 1, and stay
in lockstep through the whole table. Jitter would smear that cohort, but smearing is not
bounding — **herd control belongs to per-endpoint concurrency limiting (§7b), which caps
what a recovering receiver actually sees.** Solving it in one place beats half-solving it in
two. This makes §7b load-bearing rather than a nice-to-have.

**Timeout budget.** Three clocks, each bounding the next. These govern the *outbound*
delivery request only (us → receiver); ingest (sender → us) returns 202 in milliseconds and
is deliberately decoupled from all of it.

| clock | value | why |
|---|---|---|
| connect timeout | 3 s | DNS + TCP + TLS. A receiver that cannot handshake in 3 s is down, not slow. |
| request timeout | 30 s | Matches Svix. This number *is* the ceiling on how long one bad endpoint can hold a worker — a fairness bound (I4), not politeness. |
| lease duration | 60 s | Must exceed connect + request + margin, or the reaper (§5) re-dispatches deliveries that are still genuinely in flight and manufactures duplicates. **Derived** from the two above. |

This pins the `N` left open in §5: leases older than **60 s** are reaped.

30 s is generous, and `BROKE.md` will likely say so. It is kept deliberately: week 5's job
is to make the starvation failure *visible*, and a tight timeout would mask it. Tuning the
timeout down is not the fix — it trades one starvation rate for another without bounding
how much of the pool one endpoint can hold. Only §7b does that.

Advertise a tighter budget to receivers than we enforce (Svix documents 15 s, enforces 30 s),
so a receiver slightly over its stated limit still succeeds. Goes in the §8 receiver docs.

*Implementation note (week 3):* confirm whether JDK `HttpClient`'s request timeout subsumes
connection establishment or is additive with `connectTimeout`. The lease budget assumes a
worst case of ~33 s; verify before trusting it.

## 7. The three problems worth thinking hardest about

These have no textbook answer. Don't let them get designed away.

**7a. Ordering vs. head-of-line blocking. — DECIDED, see `DECISIONS.md` #7.**

*The question.* Events 1, 2, 3 queued for one endpoint. Event 1 fails. Retry 1 before
sending 2 (ordering preserved, but one poison event stalls that customer for the full ~28 h
retry window from §6)? Or send 2 anyway (throughput preserved, but the receiver may apply
`subscription.cancelled` before `subscription.created`)?

**Decision: best-effort ordering, explicitly not guaranteed.** Deliveries are dispatched in
`next_attempt_at` order, so the happy path arrives in order. Parallel workers and retries
break it, and that is documented rather than fought.

Three things decided it.

1. **Ordering scope and concurrency are the same knob.** Serialisation is required *within*
   a partition, so concurrency equals the number of partitions:

   | scope | in-flight allowed |
   |---|---|
   | global | 1, service-wide |
   | per-tenant | 1 per tenant |
   | per-endpoint | 1 per endpoint |
   | per-key (e.g. `subscription_id`) | 1 per key |

   Strict per-endpoint ordering therefore caps that endpoint at **1 ÷ latency** deliveries
   per second — 10/s at a 100 ms round trip, regardless of how many workers exist. Above
   that the backlog grows without bound. This is a **steady-state** cost paid on a perfectly
   healthy endpoint, which makes it worse than the head-of-line stall, a cost only paid when
   something breaks.

2. **We can only offer ordered *dispatch*, not ordered *processing*.** §8's receiver guidance
   tells the receiver to ack fast and process asynchronously — at which point their own job
   queue reorders whatever we carefully sequenced. Shipping a flag called "ordered delivery"
   would cost 1× concurrency for a promise that breaks downstream anyway, and receivers would
   build on it.

3. **The receiver-side fix is strictly better and they are already building it.** Receivers
   must dedupe on `event_id` for at-least-once (I2). Also ignoring stale updates — via a
   version/timestamp in the payload, or re-fetching current state from the sender's API as
   Stripe recommends — is a small marginal ask on a mechanism that already exists, and unlike
   our ordering it survives their async processing. Events that are order-sensitive are
   order-sensitive because they are *deltas*; versioned state removes the sensitivity.

*What we'd offer as a per-endpoint option (named, not built in v1):* an opt-in `ordering_key`
supplied at ingest — serialise within a key, parallelise across keys. Head-of-line blocking
still exists but the blast radius shrinks from "this endpoint receives nothing for 28 h" to
"this one subscription is stuck," which is usually acceptable because events about different
resources were never order-sensitive to each other. Svix ships exactly this tiering: regular
endpoints are best-effort, FIFO endpoints are opt-in.

**7b. Per-endpoint fairness. — PREDICTION, written 2026-08-08 before any measurement.**
Revise in week 6 against the week-5 load test and record the delta in `BROKE.md`.

*The question.* A naive worker pool claiming the oldest N pending rows will fill every worker
with one hanging endpoint's deliveries. Options: cap concurrent in-flight per endpoint;
round-robin claim across endpoints; per-endpoint queues with a scheduler.

*Predicted answer: cap concurrent in-flight per endpoint, enforced in the claim query.*

Round-robin makes claiming *fair* but bounds nothing — 20 workers can still all land on one
endpoint. Only a cap puts a ceiling on occupancy, and three other decisions have already
assigned §7b that job (see below), so it is the only option that satisfies them.

Enforced in Postgres, not in worker memory. An in-memory semaphore caps per *process*, and
`ROADMAP.md`'s scaling story is "more worker instances" — three workers each allowing 5 means
the endpoint sees 15. It also fights the lease: the row is already claimed before the worker
discovers it must wait. Postgres is the single source of truth for `state = 'in_flight'`,
consistent with `DECISIONS.md` #1. Mechanism: rank candidates with
`ROW_NUMBER() OVER (PARTITION BY endpoint_id ORDER BY next_attempt_at)` in the claim query and
take only those under the cap.

*Sizing the cap.* `pool_size ÷ cap` is the number of simultaneously-broken endpoints it takes
to starve the pool. 20 workers with a cap of 5 → four bad endpoints locks everything; cap of 2
→ ten. The cap is a **blast-radius** knob, not a throughput knob.

**§7b is the keystone.** Three separate decisions delegate work to it, which is why it must
bound rather than merely balance:
- §6 / `DECISIONS.md` #5 — no jitter, so herd control lives here.
- §6 / `DECISIONS.md` #6 — a generous 30 s timeout is only safe if occupancy is bounded here.
- §7c below — held deliveries flood out on breaker close; the drain rate is bounded here.

*What I expect to be wrong about:* that a static cap is enough. Suspect the right cap depends
on observed endpoint latency, and that backlog size and claim share are coupled in a way a
flat cap does not address.

**7c. Circuit breaking. — PREDICTION, written 2026-08-08.** Revise in week 8.

*Predicted answer.* Open after **5 consecutive failures** — fast, because the breaker's job is
to stop wasting workers *now*. Pair it with a separate, far slower endpoint-disable (Svix uses
120 h of continuous failure) because disabling a customer's endpoint is destructive and a blip
must not trigger it. Two mechanisms on two timescales — the same split as §6's retry window vs.
long-outage handling.

*Queued deliveries while open: **held**.* Not dropped — that violates I3 outright. Not
fast-failed to `dead`, which I3 permits but which destroys the meaning of `dead`: it should
signal "we genuinely tried for 28 h and genuinely failed," a thing an operator investigates. If
a 40-minute outage produces 50,000 dead deliveries, nobody reads the DLQ again and I3's
"visible and replayable" becomes theatre. Held keeps `dead` rare and therefore meaningful; the
cost is `delivery` table depth, which is the cheaper currency.

Two consequences:

1. **`attempt_count` must not increment while open.** Otherwise held deliveries burn their
   whole 8-attempt budget during the outage and reach `dead` having never been attempted —
   strictly worse than fast-failing. Held means the clock stops, not just the requests.
2. **On close, the entire held backlog releases at once** — the synchronised cohort from
   `DECISIONS.md` #5, now guaranteed rather than merely possible. Survivable only because
   §7b bounds the drain rate.

*Half-open probing:* let exactly **one** delivery through, not the queue. Success closes the
breaker; failure re-opens it and restarts the timer.

*What I expect to be wrong about:* the threshold of 5, and whether "consecutive failures"
is even the right trigger — Svix uses duration, not count, for disabling. A count trips on a
burst; a duration ignores blips. The breaker may want one and the disable the other.

## 8. Signing

`X-Webhook-Signature: t=<unix>,v1=<hex hmac-sha256 of "{t}.{body}">`, secret per endpoint.
Receiver rejects if `|now - t| > 5min` (replay protection) or the HMAC doesn't match.
Compare in constant time. Ship a documented verification snippet — the receiver's
experience is part of the product.

## 9. Out of scope

Multi-region, HA, self-serve signup, billing, RBAC, payload transformation DSL, external
brokers, global ordering. Re-read this list whenever a session feels like it's sprawling.

## 10. Open questions

- Should `delivery_attempt` be partitioned or pruned? At what volume does it matter?
- Per-tenant rate limiting on ingest — needed for v1, or is that scope creep?
- Does the worker live in the same process as the API for v1? (Yes — but write down the
  seam that would let it split out.)
