package com.webhookreplay.domain;

/**
 * Mirrors the CHECK constraint {@code delivery_state_valid} in V1, and the state machine
 * in DESIGN.md §5:
 *
 * <pre>
 *           ┌──────────────────────────── retry scheduled ──────────────┐
 *           v                                                           │
 *       PENDING ──claim──> IN_FLIGHT ──2xx──> DELIVERED                 │
 *                               │                                       │
 *                               ├──non-2xx / timeout──> RETRYING ───────┘
 *                               │
 *                               └──attempts exhausted──> DEAD ──replay──> PENDING
 * </pre>
 *
 * <p>{@code DELIVERED} and {@code DEAD} are the only terminal states, and {@code DEAD} is
 * terminal only until someone replays it. That is invariant I3: a delivery is never
 * abandoned silently.
 */
public enum DeliveryState {
	PENDING,
	IN_FLIGHT,
	DELIVERED,
	RETRYING,
	DEAD
}
