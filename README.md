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

Requires Java 21 and a Docker daemon.

```bash
docker compose up -d                                  # Postgres 16 on host port 5433
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # http://localhost:8080
curl localhost:8080/health                            # -> 200, {"status":"UP", ...}
./mvnw verify                                         # full build; tests bring their own Postgres
```

The `local` profile supplies development API keys. It is the only place they exist — the
packaged configuration ships none on purpose, because Spring merges `Map` properties across
sources rather than replacing them, so a committed key could never be removed by an operator
supplying their own.

Host port 5433 rather than 5432 is deliberate — see [BROKE.md](BROKE.md). Override with
`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` if you need to.

### 1. Register a receiver

<!-- TODO (week 3): replace this direct insert with a POST /v1/endpoints example once
     endpoint registration exists. -->

`POST /v1/endpoints` lands in week 3, so for now insert one directly. An event with no
matching endpoint is still accepted, so doing this first is what makes step 2 show anything:

```bash
docker compose exec postgres psql -U webhook -d webhook_replay -c \
  "insert into endpoint (tenant_id, url, signing_secret)
   values ('11111111-1111-1111-1111-111111111111', 'https://example.test/hooks', 'whsec_demo');"
```

### 2. Send an event

Senders authenticate with a static per-tenant API key; `sk_test_alice` maps to the tenant
used above.

```bash
curl -i -X POST localhost:8080/v1/events \
  -H 'Authorization: Bearer sk_test_alice' \
  -H 'Content-Type: application/json' \
  -d '{"type":"order.paid","payload":{"order_id":42,"amount":"9.99"}}'

# HTTP/1.1 202
# {"event_id":"1eeefe3a-8b2b-4a8b-8b18-293351e063b4"}
```

### 3. Check the fan-out

The 202 means the event and one `delivery` row per matching endpoint are **committed**, not
that anything has been delivered yet — that is invariant I1, and it is the whole point of
keeping the queue in Postgres:

```bash
docker compose exec postgres psql -U webhook -d webhook_replay -c \
  'select d.state, e.type, ep.url from delivery d
     join event e on e.id = d.event_id
     join endpoint ep on ep.id = d.endpoint_id;'
```

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
