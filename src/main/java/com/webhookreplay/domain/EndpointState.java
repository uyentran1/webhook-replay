package com.webhookreplay.domain;

/**
 * Mirrors the CHECK constraint {@code endpoint_state_valid} in V1.
 *
 * <p>{@code CIRCUIT_OPEN} and {@code DISABLED} are two different timescales, not two names
 * for the same thing (DESIGN.md §7c): the breaker opens after a handful of consecutive
 * failures and closes itself, while disabling is a slow, destructive, operator-visible
 * decision about an endpoint that is simply gone.
 */
public enum EndpointState {
	ACTIVE,
	CIRCUIT_OPEN,
	DISABLED
}
