package com.webhookreplay;

import com.webhookreplay.domain.Delivery;
import com.webhookreplay.domain.DeliveryAttempt;
import com.webhookreplay.domain.DeliveryState;
import com.webhookreplay.domain.Endpoint;
import com.webhookreplay.domain.EndpointState;
import com.webhookreplay.domain.Event;
import com.webhookreplay.repository.DeliveryAttemptRepository;
import com.webhookreplay.repository.DeliveryRepository;
import com.webhookreplay.repository.EndpointRepository;
import com.webhookreplay.repository.EventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ddl-auto=validate} already proves the entities and the V1 migration agree on
 * table and column names and on types — it runs at context startup, so a mismatch fails
 * every test in the suite, not just this one.
 *
 * <p>What validate cannot see is whether <em>values</em> survive the round trip. Three
 * mappings here are non-obvious enough to be worth pinning: the lowercase state converter
 * (an uppercase write would violate the CHECK constraint), {@code jsonb}, and
 * {@code text[]}. This test is the thing that fails if one of those is silently wrong.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SchemaMappingIT {

	@Autowired
	EndpointRepository endpoints;

	@Autowired
	EventRepository events;

	@Autowired
	DeliveryRepository deliveries;

	@Autowired
	DeliveryAttemptRepository attempts;

	@Autowired
	JdbcTemplate jdbc;

	@PersistenceContext
	EntityManager entityManager;

	@Test
	void allFourTablesRoundTrip() {
		UUID tenantId = UUID.randomUUID();

		Endpoint endpoint = new Endpoint(tenantId, "https://example.test/hooks", "whsec_abc123");
		endpoint.setEventTypes(new String[] { "order.paid", "order.refunded" });
		endpoint.setDescription("orders");
		endpoints.save(endpoint);

		Event event = new Event(tenantId, "order.paid", "{\"order_id\": 42, \"amount\": \"9.99\"}");
		event.setIdempotencyKey("idem-1");
		events.save(event);

		Delivery delivery = new Delivery(event, endpoint);
		deliveries.save(delivery);

		attempts.save(DeliveryAttempt.responded(delivery, 1, 137, 502, "Bad Gateway"));

		// Force the INSERTs, then empty the persistence context so the reads below come
		// from Postgres rather than Hibernate's first-level cache — otherwise this would
		// assert that Java remembers what Java just set, which proves nothing.
		entityManager.flush();
		entityManager.clear();

		Endpoint reloaded = endpoints.findById(endpoint.getId()).orElseThrow();
		assertThat(reloaded.getEventTypes()).containsExactly("order.paid", "order.refunded");
		assertThat(reloaded.getState()).isEqualTo(EndpointState.ACTIVE);
		assertThat(reloaded.getConsecutiveFailures()).isZero();
		assertThat(reloaded.getCircuitOpenedAt()).isNull();

		Event reloadedEvent = events.findById(event.getId()).orElseThrow();
		assertThat(reloadedEvent.getPayload()).contains("\"order_id\"");
		assertThat(reloadedEvent.getIdempotencyKey()).isEqualTo("idem-1");

		Delivery reloadedDelivery = deliveries.findById(delivery.getId()).orElseThrow();
		assertThat(reloadedDelivery.getState()).isEqualTo(DeliveryState.PENDING);
		assertThat(reloadedDelivery.getAttemptCount()).isZero();
		assertThat(reloadedDelivery.getNextAttemptAt()).isNotNull();
		assertThat(reloadedDelivery.getLockedAt()).isNull();
		// Lazy associations resolve inside the transaction; the id alone doesn't trigger a load.
		assertThat(reloadedDelivery.getEndpoint().getId()).isEqualTo(endpoint.getId());

		DeliveryAttempt reloadedAttempt = attempts.findAll().getFirst();
		assertThat(reloadedAttempt.getStatusCode()).isEqualTo(502);
		assertThat(reloadedAttempt.getError()).isNull();
		assertThat(reloadedAttempt.getLatencyMs()).isEqualTo(137);
		assertThat(reloadedAttempt.getId()).isNotNull();
	}

	/**
	 * The states are stored lowercase because that is what the CHECK constraint and the
	 * wire format say. {@code @Enumerated(STRING)} would have written {@code PENDING} here.
	 */
	@Test
	void statesAreStoredLowercase() {
		UUID tenantId = UUID.randomUUID();
		Endpoint endpoint = endpoints.save(new Endpoint(tenantId, "https://example.test/x", "whsec_x"));
		Event event = events.save(new Event(tenantId, "order.paid", "{}"));
		Delivery delivery = deliveries.save(new Delivery(event, endpoint));
		entityManager.flush();

		assertThat(jdbc.queryForObject("select state from endpoint where id = ?", String.class, endpoint.getId()))
				.isEqualTo("active");
		assertThat(jdbc.queryForObject("select state from delivery where id = ?", String.class, delivery.getId()))
				.isEqualTo("pending");
	}

	/**
	 * Both halves of the idempotency constraint. The NULL case is the one that would bite:
	 * if Postgres treated NULLs as equal, the second keyless event from the same tenant
	 * would be rejected and ingest would break for every sender that omits the header.
	 */
	@Test
	void idempotencyKeyIsUniquePerTenantButNullsDoNotCollide() {
		UUID tenantId = UUID.randomUUID();

		events.save(new Event(tenantId, "order.paid", "{}"));
		events.save(new Event(tenantId, "order.paid", "{}"));
		entityManager.flush();

		Event first = new Event(tenantId, "order.paid", "{}");
		first.setIdempotencyKey("idem-dup");
		events.save(first);
		entityManager.flush();

		Event second = new Event(tenantId, "order.paid", "{}");
		second.setIdempotencyKey("idem-dup");

		// saveAndFlush, not save + entityManager.flush(). Spring translates Hibernate's
		// ConstraintViolationException into the vendor-neutral DataIntegrityViolationException
		// at the repository proxy boundary; flushing the EntityManager directly goes around
		// that proxy and throws the Hibernate exception raw. The week-9 ingest path calls the
		// repository, so this is the exception it will actually have to catch.
		assertThatThrownBy(() -> events.saveAndFlush(second))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
