# Roadmap

**Budget:** 6 h/week — one ~2h weeknight session (S1), one ~4h weekend session (S2).
**Target:** feature-complete around week 14 (~3.5 months).
**Checkpoint:** after week 10 this is CV-presentable with no UI at all. If anything forces
a stop, stop there — a deeply understood backend with load-test numbers beats a
half-finished full stack.

Plan 14 weeks, expect 18. A missed week is not failure; four consecutive missed weeks is.

---

## Phase 0 — Design (week 1)

- [x] **S1** Finish `DESIGN.md` §6 retry policy and §7 open problems (your own answers, not Claude's)
- [x] **S2** `git init`, Spring Boot skeleton, docker-compose (Postgres), Flyway wired, health endpoint, first commit

## Phase 1 — Durable ingest and naive delivery (weeks 2–4)

**Week 2**
- [ ] **S1** Migration V1: all four tables. Entities + repositories.
- [ ] **S2** `POST /v1/events` → persist → 202. API-key auth filter. Fan-out `delivery` rows on ingest. Integration test with Testcontainers.

**Week 3**
- [ ] **S1** `POST/GET /v1/endpoints`. Endpoint registration returns a signing secret.
- [ ] **S2** Worker: claim loop with `SELECT … FOR UPDATE SKIP LOCKED`, POST via `HttpClient` with explicit timeouts, write `delivery_attempt` row, mark delivered/retrying.

**Week 4**
- [ ] **S1** Retry schedule table (`DESIGN.md` §6). Unit-test the schedule and attempt 8 → `dead`.
- [ ] **S2** State machine hardening: lease expiry reaper, attempt cap → `dead`. Integration tests for each transition.

> End of Phase 1: a correct, boring, working system. Tag it `v0.1-naive`.

## Phase 2 — Break it on purpose (week 5) ← the important week

- [ ] **S1** Flaky receiver simulator: configurable mix of 200s, 500s, 30s hangs, slow-drip responses
- [ ] **S2** k6 load test against it. **Watch one hanging endpoint starve every other delivery.** Record numbers: throughput, p99 delivery latency, how many healthy endpoints were affected
- [ ] Write the `BROKE.md` entry with actual figures — this is the entry that matters most

## Phase 3 — The hard parts (weeks 6–9)

**Week 6** — Per-endpoint fairness / noisy-neighbour isolation
- [ ] **S1** Decide the approach (see `DESIGN.md` §7b), write it in `DECISIONS.md` first
- [ ] **S2** Implement, re-run the week-5 load test, record the before/after in `BROKE.md`

**Week 7** — Signing
- [ ] **S1** HMAC-SHA256 with timestamp, constant-time compare
- [ ] **S2** Timestamp tolerance + replay rejection, receiver verification docs and snippet

**Week 8** — Circuit breaking
- [ ] **S1** Open on N consecutive failures; decide what happens to queued deliveries
- [ ] **S2** Half-open recovery probing without a thundering herd; tests

**Week 9** — DLQ and idempotency
- [ ] **S1** `dead` state surfaced via API + `POST /deliveries/{id}/replay`
- [ ] **S2** Ingest `Idempotency-Key` handling; concurrent-duplicate test

## Phase 4 — Observability (week 10) ← CV-presentable checkpoint

- [ ] **S1** Micrometer metrics: attempts, outcomes by status class, delivery latency histogram, queue depth, per-endpoint success rate
- [ ] **S2** Prometheus + Grafana in docker-compose, dashboard, re-run load test, screenshot before/after for the README
- [ ] Tag `v0.5-backend-complete`

## Phase 5 — React console (weeks 11–13)

**Week 11**
- [ ] **S1** Vite + TS + Tailwind scaffold, typed API client, auth
- [ ] **S2** Endpoint list + create/edit, secret reveal-once flow

**Week 12**
- [ ] **S1** Delivery log: filters by endpoint, state, time range; pagination
- [ ] **S2** Per-event attempt timeline (attempt 1 → 502 → retry in 4s → attempt 2 → 200). The single best screen in the app — spend the time.

**Week 13**
- [ ] **S1** SSE live tail
- [ ] **S2** One-click replay, endpoint health cards with p50/p99 and success rate (recharts)

## Phase 6 — Ship and write up (week 14)

- [ ] **S1** Deploy (single VM or Fly.io), TLS, GitHub Actions CI running tests on PR
- [ ] **S2** README: architecture diagram, link to `DESIGN.md`, and the **failure modes** section built from `BROKE.md` with the load-test graphs. Tag `v1.0`.

Optional, high leverage: a short blog post walking through the week-5 → week-6 story. It's
the cheapest way to make the project legible to someone skimming for 30 seconds.

---

## Things that will kill this project

In descending order of likelihood:

1. Infra yak-shaving (Terraform, Kubernetes, multi-stage Docker optimisation). Deploy boring.
2. Ending a session mid-refactor so the next one starts with 40 minutes of debugging.
3. Scope creep into the §9 out-of-scope list.
4. Polishing the UI before the backend has produced messy real data worth looking at.
5. Never reaching week 5 — the only genuinely fatal one.
