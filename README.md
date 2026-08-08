# webhook-replay

A self-hostable webhook delivery service. You POST events to it; it takes responsibility
for getting them to your customers' endpoints — durably, with retries, signatures,
per-endpoint isolation, and a delivery log you can actually debug from.

> 🚧 In active development. See [ROADMAP.md](ROADMAP.md) for where it is.

## Why

If your product has events other developers care about, you eventually have to build
webhook infrastructure. The naive version is one line inside a request handler, and it
fails in six escalating ways: lost events when the receiver is down, your latency held
hostage to theirs, duplicate processing on retry, forgeable requests, one dead receiver
starving everyone else's deliveries, and no answer to "we never got that webhook."

This is the thing you use instead of building that. See [DESIGN.md](DESIGN.md) for the
full problem statement and the invariants it guarantees.

## What it does

- **Durable ingest** — an event accepted with 202 is persisted before the response returns
- **At-least-once delivery** with an explicit retry schedule spanning ~28 hours
- **Per-endpoint isolation** — one hanging receiver can't starve deliveries to others
- **HMAC-SHA256 signatures** with timestamp tolerance for replay protection
- **Circuit breaking** on persistently dead endpoints, with recovery probing
- **Dead-letter queue** with one-click replay
- **Full attempt log** — every attempt with status code, latency, and response body snippet
- **Operator console** — delivery log, per-event attempt timeline, live tail, health dashboard

## Quick start

```bash
docker compose up -d
./mvnw spring-boot:run
```

<!-- TODO: register-an-endpoint + send-an-event curl example once the API exists -->

## Architecture

<!-- TODO (week 14): diagram. Ingest → Postgres → claim loop (SKIP LOCKED) → delivery
     workers → attempt log, with the operator console reading from the same tables. -->

Notable choices, with the rejected alternatives, are in [DECISIONS.md](DECISIONS.md).
The short version: **Postgres is the queue.** Delivery scheduling uses
`SELECT … FOR UPDATE SKIP LOCKED` rather than an external broker, which keeps event
persistence and delivery scheduling in a single transaction and avoids the dual-write
problem entirely.

## Failure modes

<!-- TODO (week 14): the most important section of this README.

     Built from BROKE.md. For each: what I broke on purpose, what I measured, what I
     changed, and the before/after numbers. Include the Grafana screenshots from week 10.

     Load-tested against a deliberately flaky receiver (random 500s, 30s hangs, slow
     responses) using k6. -->

## Known limitations

- At-least-once, not exactly-once. Receivers must dedupe on `event_id`; this is a property
  of the problem, not a shortcut. A worker whose lease expires mid-request can cause a
  second attempt while the first is still in flight.
- No global ordering guarantee.
- Single-region, single Postgres. Scaling story is "more worker instances."

## Verifying signatures (receiver side)

<!-- TODO: a copy-pasteable snippet in 2–3 languages. The receiver's experience is part
     of the product. -->

## License

MIT — see [LICENSE](LICENSE).
