package com.webhookreplay;

import com.webhookreplay.domain.Delivery;
import com.webhookreplay.domain.DeliveryState;
import com.webhookreplay.domain.Endpoint;
import com.webhookreplay.domain.EndpointState;
import com.webhookreplay.domain.Event;
import com.webhookreplay.repository.DeliveryRepository;
import com.webhookreplay.repository.EndpointRepository;
import com.webhookreplay.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The spec for week 2 S2: ingest is durable and fanned out before the 202 comes back.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}, unlike {@link SchemaMappingIT}. A test
 * transaction would let the controller join it and roll everything back at the end, so the
 * assertions would pass against rows that never committed — which is precisely the thing
 * invariant I1 is about. Rows are cleaned up explicitly instead.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { TestApiKeys.ALICE_PROPERTY, TestApiKeys.BOB_PROPERTY })
@AutoConfigureMockMvc
class EventIngestIT {

	private static final String API_KEY = TestApiKeys.ALICE;

	private static final UUID TENANT = UUID.fromString(TestApiKeys.ALICE_TENANT);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EndpointRepository endpoints;

	@Autowired
	EventRepository events;

	@Autowired
	DeliveryRepository deliveries;

	@BeforeEach
	void clean() {
		// FK order: delivery references both of the others.
		deliveries.deleteAll();
		events.deleteAll();
		endpoints.deleteAll();
	}

	@Test
	void acceptsAnEventAndFansOutOneDeliveryPerMatchingEndpoint() throws Exception {
		Endpoint endpoint = endpoints.save(new Endpoint(TENANT, "https://example.test/hooks", "whsec_a"));

		mockMvc.perform(post("/v1/events")
						.header("Authorization", "Bearer " + API_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"type": "order.paid", "payload": {"order_id": 42}}"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.event_id").exists());

		// I1: by the time the 202 was written, both rows were committed. Read them back
		// through fresh queries rather than trusting the response body.
		List<Event> stored = events.findAll();
		assertThat(stored).hasSize(1);
		assertThat(stored.getFirst().getTenantId()).isEqualTo(TENANT);
		assertThat(stored.getFirst().getType()).isEqualTo("order.paid");

		List<Delivery> fanOut = deliveries.findAll();
		assertThat(fanOut).hasSize(1);
		Delivery delivery = fanOut.getFirst();
		assertThat(delivery.getEndpoint().getId()).isEqualTo(endpoint.getId());
		assertThat(delivery.getEvent().getId()).isEqualTo(stored.getFirst().getId());
		// Claimable the instant it commits — week 3's claim loop looks for exactly this.
		assertThat(delivery.getState()).isEqualTo(DeliveryState.PENDING);
		assertThat(delivery.getAttemptCount()).isZero();
	}

	/**
	 * NULL means "all types". The distinction from an empty array is enforced nowhere in the
	 * schema, so this test is the only thing standing between a no-filter endpoint and
	 * silently receiving nothing — {@code x = any(null)} is NULL, not false, so a query
	 * without an explicit null branch drops exactly these endpoints.
	 */
	@Test
	void anEndpointWithNoFilterReceivesEveryType() throws Exception {
		endpoints.save(new Endpoint(TENANT, "https://example.test/all", "whsec_all"));

		postEvent("anything.at.all").andExpect(status().isAccepted());

		assertThat(deliveries.findAll()).hasSize(1);
	}

	/**
	 * The positive half of the filter, and not redundant with the tests around it: every
	 * other case here uses either a null filter or expects no delivery, so a
	 * {@code :type = any(event_types)} branch that never matched anything would leave the
	 * whole suite green. This is the only test that fails if that expression is wrong.
	 */
	@Test
	void deliversWhenTheTypeIsListedInTheFilter() throws Exception {
		Endpoint filtered = new Endpoint(TENANT, "https://example.test/orders", "whsec_o");
		filtered.setEventTypes(new String[] { "order.refunded", "order.paid" });
		endpoints.save(filtered);

		postEvent("order.paid").andExpect(status().isAccepted());

		assertThat(deliveries.findAll()).hasSize(1);
	}

	@Test
	void doesNotDeliverToAnEndpointFilteredToOtherTypes() throws Exception {
		Endpoint filtered = new Endpoint(TENANT, "https://example.test/refunds", "whsec_r");
		filtered.setEventTypes(new String[] { "order.refunded" });
		endpoints.save(filtered);

		postEvent("order.paid").andExpect(status().isAccepted());

		assertThat(events.findAll()).hasSize(1);
		assertThat(deliveries.findAll()).isEmpty();
	}

	/**
	 * Only {@code active} endpoints fan out. Worth noting what this means for week 8: an
	 * event arriving while a breaker is open produces no delivery row <em>at all</em>, but
	 * DESIGN.md §7c wants those deliveries <em>held</em> rather than dropped. This test
	 * pins today's behaviour, and the circuit-breaker work will have to change it
	 * deliberately rather than discover it.
	 */
	@Test
	void doesNotDeliverToDisabledOrCircuitOpenEndpoints() throws Exception {
		Endpoint disabled = new Endpoint(TENANT, "https://example.test/gone", "whsec_d");
		disabled.setState(EndpointState.DISABLED);
		endpoints.save(disabled);

		Endpoint tripped = new Endpoint(TENANT, "https://example.test/flaky", "whsec_c");
		tripped.setState(EndpointState.CIRCUIT_OPEN);
		endpoints.save(tripped);

		postEvent("order.paid").andExpect(status().isAccepted());

		assertThat(deliveries.findAll()).isEmpty();
	}

	/**
	 * The isolation test. Alice's key must never reach Bob's endpoint — the tenant comes
	 * from the authenticated principal and is never accepted from the request, so there is
	 * no input a sender could craft to cross this line.
	 */
	@Test
	void doesNotDeliverToAnotherTenantsEndpoint() throws Exception {
		UUID otherTenant = UUID.fromString(TestApiKeys.BOB_TENANT);
		endpoints.save(new Endpoint(otherTenant, "https://bob.test/hooks", "whsec_bob"));

		postEvent("order.paid").andExpect(status().isAccepted());

		assertThat(deliveries.findAll()).isEmpty();
	}

	/**
	 * Zero matches is a successful ingest, not an error. I2 promises an attempt per active
	 * matching endpoint and there may be none; the event still has to be durable, because
	 * that is what makes it possible to register an endpoint later and replay history to it.
	 */
	@Test
	void acceptsAnEventWithNoMatchingEndpointsAtAll() throws Exception {
		postEvent("order.paid")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.event_id").exists());

		assertThat(events.findAll()).hasSize(1);
		assertThat(deliveries.findAll()).isEmpty();
	}

	/**
	 * Binding {@code payload} to a {@code JsonNode} does not on its own guarantee an object:
	 * a bare JSON string parses cleanly and stores a jsonb <em>scalar</em>, against which
	 * {@code payload ->> 'order_id'} returns nothing. That is the week-12 delivery-log
	 * failure, and it used to be accepted with a 202.
	 */
	@Test
	void rejectsAPayloadThatIsNotAnObject() throws Exception {
		postBody("""
				{"type": "order.paid", "payload": "just a string"}""")
				.andExpect(status().isBadRequest());

		assertThat(events.findAll()).isEmpty();
	}

	/**
	 * Jackson accepts an escaped NUL inside a string and re-emits the escape; Postgres
	 * {@code jsonb} refuses it outright. Without the guard in {@code IngestRequest} this is a
	 * 500 and a rolled-back transaction from sender-controlled input — and escaped NULs are
	 * ordinary in anything relaying a database text column.
	 */
	@Test
	void rejectsAnEscapedNulInThePayload() throws Exception {
		postBody("{\"type\": \"order.paid\", \"payload\": {\"note\": \"a\\u0000b\"}}")
				.andExpect(status().isBadRequest());

		assertThat(events.findAll()).isEmpty();
	}

	@Test
	void rejectsAMissingType() throws Exception {
		postBody("""
				{"payload": {"order_id": 1}}""")
				.andExpect(status().isBadRequest());

		assertThat(events.findAll()).isEmpty();
	}

	private ResultActions postEvent(String type) throws Exception {
		return postBody("{\"type\": \"%s\", \"payload\": {\"order_id\": 42}}".formatted(type));
	}

	private ResultActions postBody(String body) throws Exception {
		return mockMvc.perform(post("/v1/events")
				.header("Authorization", "Bearer " + API_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

}
