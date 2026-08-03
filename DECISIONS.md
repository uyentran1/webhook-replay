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

<!-- Template — copy for each new decision

## N. Title

**Date:**

**Decision:**

**Rejected:**

**Why:**

**Revisit when:**

-->
