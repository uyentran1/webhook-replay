package com.webhookreplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One HTTP request to one receiver. Append-only: written once, never updated, never
 * deleted. This is invariant I5, and it is the table that makes the week-12 attempt
 * timeline worth building instead of CRUD.
 *
 * <p>No setters, for that reason — an attempt is a fact about something that already
 * happened.
 */
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {

	/**
	 * {@code bigserial}, not a UUID: these are written far more often than they are
	 * addressed by id, always read via {@code delivery_id}, and a monotonic key keeps
	 * inserts appending to the end of the index rather than scattering across it.
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", nullable = false)
	private Delivery delivery;

	@Column(name = "attempt_no", nullable = false)
	private int attemptNo;

	/** Null when no HTTP exchange completed — a timeout or a connection failure. */
	@Column(name = "status_code")
	private Integer statusCode;

	/** Null when {@link #statusCode} is set. The transport-level reason we have no status. */
	private String error;

	/**
	 * Measured around the whole request, so it stays meaningful when there is no status
	 * code: a timeout's latency is the timeout itself, which is exactly the number week 5
	 * needs to show one hanging endpoint holding a worker for 30 s.
	 */
	@Column(name = "latency_ms", nullable = false)
	private int latencyMs;

	@Column(name = "response_snippet")
	private String responseSnippet;

	@Column(name = "attempted_at", nullable = false)
	private Instant attemptedAt = Instant.now();

	protected DeliveryAttempt() {
	}

	private DeliveryAttempt(Delivery delivery, int attemptNo, int latencyMs) {
		this.delivery = delivery;
		this.attemptNo = attemptNo;
		this.latencyMs = latencyMs;
	}

	/** The receiver answered, whatever it said. */
	public static DeliveryAttempt responded(Delivery delivery, int attemptNo, int latencyMs,
			int statusCode, String responseSnippet) {
		DeliveryAttempt attempt = new DeliveryAttempt(delivery, attemptNo, latencyMs);
		attempt.statusCode = statusCode;
		attempt.responseSnippet = responseSnippet;
		return attempt;
	}

	/** No HTTP response: timeout, connection refused, TLS failure. */
	public static DeliveryAttempt failed(Delivery delivery, int attemptNo, int latencyMs, String error) {
		DeliveryAttempt attempt = new DeliveryAttempt(delivery, attemptNo, latencyMs);
		attempt.error = error;
		return attempt;
	}

	public Long getId() {
		return id;
	}

	public Delivery getDelivery() {
		return delivery;
	}

	public int getAttemptNo() {
		return attemptNo;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public String getError() {
		return error;
	}

	public int getLatencyMs() {
		return latencyMs;
	}

	public String getResponseSnippet() {
		return responseSnippet;
	}

	public Instant getAttemptedAt() {
		return attemptedAt;
	}

}
