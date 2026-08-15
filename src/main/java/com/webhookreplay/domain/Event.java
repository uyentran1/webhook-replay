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
	 * Held as an opaque string, not a parsed tree. We never inspect or transform payloads
	 * (a transformation DSL is out of scope, §9), and the bytes we sign in §8 must be
	 * exactly the bytes we received — round-tripping through a JSON model risks reordering
	 * keys and invalidating the signature.
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
