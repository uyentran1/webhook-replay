-- V1: the four tables of DESIGN.md §4.
--
-- Shape to keep in mind: `event` is what arrived, `delivery` is the fan-out (one event ->
-- N deliveries, one per matching endpoint), `delivery_attempt` is the immutable log.
--
-- NO INDEXES HERE, deliberately. DESIGN.md §4 names the three that will matter, but the
-- justification for each is a query that doesn't exist yet -- the claim query lands in
-- week 3. An index added before its query is a guess, and a guess that costs write
-- throughput on every insert. They arrive with the queries that need them.
--
-- States are `text` with a CHECK rather than a Postgres ENUM type. ENUM buys a little
-- storage and definition-order sorting. It costs two things, verified against this PG 16:
-- a value added with ALTER TYPE ... ADD VALUE cannot be *used* until that transaction
-- commits ("New enum values must be committed before they can be used"), so a migration
-- cannot add a state and backfill rows into it in one step; and there is no
-- ALTER TYPE ... DROP VALUE at all, in any version, so removing or renaming a state means
-- rebuilding the type and rewriting the column.
--
-- DESIGN.md §7c describes deliveries being *held* while a breaker is open, so a sixth
-- delivery state is a plausible week-8 migration. text + CHECK keeps that a one-line
-- constraint swap.
--
-- Consequence, priced in: the lowercase labels don't match the Java constant names, so
-- @Enumerated(STRING) can't be used and the mapping goes through an AttributeConverter.
-- Uppercase labels would delete those two classes at the cost of less idiomatic SQL.
-- Decided in favour of the SQL; see DECISIONS.md #9.


create table endpoint (
    id                    uuid        primary key default gen_random_uuid(),
    tenant_id             uuid        not null,
    url                   text        not null,
    description           text,

    -- Per-endpoint, not per-tenant: the HMAC key of DESIGN.md §8. Revealed once at
    -- registration (§3) and never again, so losing it means rotating it.
    signing_secret        text        not null,

    -- NULL means "all event types". An empty array would mean "no event types" and is a
    -- different, useless thing -- the API layer must not conflate them.
    event_types           text[],

    state                 text        not null default 'active'
        constraint endpoint_state_valid
        check (state in ('active', 'circuit_open', 'disabled')),

    -- Circuit breaker bookkeeping (DESIGN.md §7c). Written in week 8; carried here so the
    -- breaker doesn't need a schema change to land.
    consecutive_failures  int         not null default 0,
    circuit_opened_at     timestamptz,

    created_at            timestamptz not null default now()
);


create table event (
    id              uuid        primary key default gen_random_uuid(),
    tenant_id       uuid        not null,

    -- Nullable on purpose. Postgres treats NULLs as distinct in a unique constraint, so
    -- senders that omit Idempotency-Key simply don't participate in dedupe rather than
    -- colliding with each other on a shared sentinel value. Exactly the semantics wanted.
    idempotency_key text,

    type            text        not null,
    payload         jsonb       not null,
    created_at      timestamptz not null default now(),

    -- Scoped to the tenant: two customers must be free to pick the same key.
    constraint event_tenant_idempotency_key_unique unique (tenant_id, idempotency_key)
);


create table delivery (
    id              uuid        primary key default gen_random_uuid(),

    event_id        uuid        not null references event (id)    on delete cascade,

    -- RESTRICT, not CASCADE: deleting an endpoint must not silently erase the delivery
    -- history that proves what was sent to it. DELETE /v1/endpoints/{id} (§3) should be a
    -- soft delete -- state -> 'disabled' -- and this constraint is what enforces that at
    -- the storage layer rather than trusting the service layer to remember.
    endpoint_id     uuid        not null references endpoint (id) on delete restrict,

    state           text        not null default 'pending'
        constraint delivery_state_valid
        check (state in ('pending', 'in_flight', 'delivered', 'retrying', 'dead')),

    -- Attempts already made, so 0 before the first. The retry schedule (§6) indexes off
    -- this, and §7c requires it NOT increment while the breaker is open.
    attempt_count   int         not null default 0,

    -- When this row becomes claimable. Set to now() on creation so a fresh delivery is
    -- immediately eligible; the claim query orders by it (§7a: ordered dispatch).
    next_attempt_at timestamptz not null default now(),

    -- The lease of DESIGN.md §5. Both NULL unless state = 'in_flight'; the reaper returns
    -- rows whose locked_at is older than 60s to 'retrying'.
    locked_at       timestamptz,
    locked_by       text,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);


-- Append-only. Never updated, never deleted -- this is the evidence behind "we never got
-- that webhook" (§1) and the per-event timeline that makes the week-12 UI worth building.
-- bigserial rather than uuid: rows are written far more often than they're addressed by
-- id, they're always read by delivery_id, and monotonic keys keep the inserts sequential.
create table delivery_attempt (
    id               bigserial   primary key,
    delivery_id      uuid        not null references delivery (id) on delete cascade,
    attempt_no       int         not null,

    -- status_code and error are mutually exclusive in practice: a completed HTTP exchange
    -- has a status, a timeout or connection failure has an error. Not expressed as a CHECK
    -- because the week-3 worker may want to record both (e.g. a 200 whose body failed to
    -- read); tighten it later if that turns out not to happen.
    status_code      int,
    error            text,

    -- Measured around the whole request, so it's meaningful even when status_code is NULL:
    -- a timeout's latency is the timeout itself, which is the number week 5 cares about.
    latency_ms       int         not null,

    response_snippet text,
    attempted_at     timestamptz not null default now(),

    constraint delivery_attempt_no_unique unique (delivery_id, attempt_no)
);
