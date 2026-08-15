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
 * A registered receiver URL. Note "endpoint" is a URL, not a customer — one tenant can
 * register several, each with its own event-type filter and its own signing secret.
 */
@Entity
@Table(name = "endpoint")
public class Endpoint {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(nullable = false)
	private String url;

	private String description;

	/** The HMAC key of DESIGN.md §8. Returned once at registration and never again. */
	@Column(name = "signing_secret", nullable = false)
	private String signingSecret;

	/**
	 * {@code null} means "all event types" — deliberately distinct from an empty array,
	 * which would mean "no event types" and would silently stop all delivery.
	 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "event_types")
	private String[] eventTypes;

	@Column(nullable = false)
	private EndpointState state = EndpointState.ACTIVE;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Column(name = "circuit_opened_at")
	private Instant circuitOpenedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected Endpoint() {
		// JPA requires a no-arg constructor; it is not part of the API.
	}

	public Endpoint(UUID tenantId, String url, String signingSecret) {
		this.tenantId = tenantId;
		this.url = url;
		this.signingSecret = signingSecret;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getSigningSecret() {
		return signingSecret;
	}

	public String[] getEventTypes() {
		return eventTypes;
	}

	public void setEventTypes(String[] eventTypes) {
		this.eventTypes = eventTypes;
	}

	public EndpointState getState() {
		return state;
	}

	public void setState(EndpointState state) {
		this.state = state;
	}

	public int getConsecutiveFailures() {
		return consecutiveFailures;
	}

	public void setConsecutiveFailures(int consecutiveFailures) {
		this.consecutiveFailures = consecutiveFailures;
	}

	public Instant getCircuitOpenedAt() {
		return circuitOpenedAt;
	}

	public void setCircuitOpenedAt(Instant circuitOpenedAt) {
		this.circuitOpenedAt = circuitOpenedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
