package com.webhookreplay;

import com.webhookreplay.domain.Delivery;
import com.webhookreplay.domain.DeliveryState;
import com.webhookreplay.domain.Endpoint;
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
@SpringBootTest
@AutoConfigureMockMvc
class EventIngestIT {

	/** Must match a key in {@code application.properties}; the tenant is resolved from it. */
	private static final String API_KEY = "sk_test_alice";

	private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

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

}
