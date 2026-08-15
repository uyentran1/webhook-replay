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
import java.util.UUID;

/**
 * One event's journey to one endpoint. The unit of work the worker claims.
 *
 * <p>Associations are {@code LAZY}: the delivery-log screen wants to navigate to the
 * endpoint, but the worker's claim loop is native SQL (DESIGN.md §7b needs a window
 * function that has no JPA expression) and must not drag two extra selects per row.
 * With {@code open-in-view=false}, touching a lazy association outside a transaction
 * throws — that is the intended pressure to fetch explicitly where it matters.
 */
@Entity
@Table(name = "delivery")
public class Delivery {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private Event event;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "endpoint_id", nullable = false)
	private Endpoint endpoint;

	@Column(nullable = false)
	private DeliveryState state = DeliveryState.PENDING;

	/** Attempts already made, so 0 before the first. Must not increment while the breaker is open (§7c). */
	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	/** When this row becomes claimable. The claim query orders by it — §7a's ordered dispatch. */
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt = Instant.now();

	/**
	 * The lease of DESIGN.md §5. Both null unless {@code state == IN_FLIGHT}; the reaper
	 * returns rows leased longer than 60 s to {@code RETRYING}. That 60 s is derived from
	 * the 3 s connect + 30 s request budget, not chosen — set it lower and the reaper
	 * manufactures duplicates of requests that are still genuinely in flight.
	 */
	@Column(name = "locked_at")
	private Instant lockedAt;

	@Column(name = "locked_by")
	private String lockedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Delivery() {
	}

	public Delivery(Event event, Endpoint endpoint) {
		this.event = event;
		this.endpoint = endpoint;
	}

	public UUID getId() {
		return id;
	}

	public Event getEvent() {
		return event;
	}

	public Endpoint getEndpoint() {
		return endpoint;
	}

	public DeliveryState getState() {
		return state;
	}

	public void setState(DeliveryState state) {
		this.state = state;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public void setAttemptCount(int attemptCount) {
		this.attemptCount = attemptCount;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public void setNextAttemptAt(Instant nextAttemptAt) {
		this.nextAttemptAt = nextAttemptAt;
	}

	public Instant getLockedAt() {
		return lockedAt;
	}

	public void setLockedAt(Instant lockedAt) {
		this.lockedAt = lockedAt;
	}

	public String getLockedBy() {
		return lockedBy;
	}

	public void setLockedBy(String lockedBy) {
		this.lockedBy = lockedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

}
