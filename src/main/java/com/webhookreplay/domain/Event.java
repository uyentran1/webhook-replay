package com.webhookreplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * What arrived. One event fans out to N {@link Delivery} rows, one per matching endpoint.
 *
 * <p>Persisting this row is what invariant I1 promises before the 202 returns.
 */
@Entity
@Table(name = "event")
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	/**
	 * Nullable: senders that omit {@code Idempotency-Key} opt out of dedupe. Postgres
	 * treats NULLs as distinct in the {@code (tenant_id, idempotency_key)} unique
	 * constraint, so they don't collide with each other.
	 */
	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(nullable = false)
	private String type;

	/**
	 * Held as an opaque string, not a parsed tree: we never inspect or transform payloads
	 * (a transformation DSL is out of scope, §9).
	 *
	 * <p>This javadoc used to claim the stored bytes must be exactly the bytes the sender
	 * sent, to keep the §8 signature valid. That was never achievable and the column type is
	 * why — {@code jsonb} normalises on write. Verified against PG 16:
	 * {@code '{"b":1, "a":2, "a":3}'::jsonb} comes back as {@code {"a": 3, "b": 1}}, with
	 * keys reordered, whitespace dropped and the duplicate collapsed. No amount of care in
	 * Java changes that.
	 *
	 * <p>The invariant that actually holds, and the one §8 needs: <strong>the bytes we sign
	 * are the bytes we send.</strong> A receiver verifies our signature against the body we
	 * handed it, and those are the same bytes, so signing is self-consistent regardless.
	 *
	 * <p><strong>The consequence to be honest about:</strong> what we POST to a receiver is
	 * not byte-identical to what the sender gave us. It is semantically the same JSON in
	 * every case but one — duplicate keys collapse last-wins, so {@code {"d":1, "d":2}} is
	 * delivered as {@code {"d": 2}}. RFC 8259 leaves that case undefined and most parsers
	 * collapse it too. Strings, numbers and precision all survive intact.
	 *
	 * <p>Storing the sender's exact bytes would buy nothing anyway: week 9 replay has to
	 * re-sign with a fresh timestamp, or the receiver rejects it as a replay attack, so a
	 * replayed delivery necessarily carries different signed bytes than the original.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected Event() {
	}

	public Event(UUID tenantId, String type, String payload) {
		this.tenantId = tenantId;
		this.type = type;
		this.payload = payload;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public String getType() {
		return type;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
