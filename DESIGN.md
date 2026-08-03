# Design: webhook-replay

> Status: **draft** — complete this in week 1 before writing application code.
> Sections marked TODO are for me to fill in; they're the parts worth thinking about.

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
`event_id`), and global ordering.

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
doesn't strand the row. A reaper returns leases older than N minutes to `retrying`.
**Consequence:** a delivery can be attempted twice if the lease expires while the request
is actually still in flight. That's at-least-once working as intended, not a bug — but say
so out loud in the README.

## 6. Retry policy

Base 2s, exponential ×2, full jitter, cap 1h, 12 attempts (~24h of coverage).

TODO: work out and write down the actual schedule, and answer: why full jitter rather than
equal jitter or none? What does the thundering-herd scenario look like when a receiver
comes back after an hour of downtime?

## 7. The three problems worth thinking hardest about

These have no textbook answer. Don't let them get designed away.

**7a. Ordering vs. head-of-line blocking.** Events 1, 2, 3 queued for one endpoint. Event
1 fails. Retry 1 before sending 2 (ordering preserved, but one poison event stalls that
customer for 24h)? Or send 2 anyway (throughput preserved, but the receiver may apply
`subscription.cancelled` before `subscription.created`)? TODO: pick, justify, and note
what you'd offer as a per-endpoint option.

**7b. Per-endpoint fairness.** A naive worker pool claiming the oldest N pending rows will
fill every worker with one hanging endpoint's deliveries. Options: cap concurrent
in-flight per endpoint; round-robin claim across endpoints; per-endpoint queues with a
scheduler. TODO: after you've observed the failure in week 5, pick one and write down why.

**7c. Circuit breaking.** How many consecutive failures before opening? What happens to
queued deliveries while open — dropped, held, or fast-failed to `dead`? How do you probe
for recovery without a thundering herd? TODO.

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
