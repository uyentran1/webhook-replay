package com.webhookreplay.service;

import com.webhookreplay.domain.Delivery;
import com.webhookreplay.domain.Endpoint;
import com.webhookreplay.domain.Event;
import com.webhookreplay.repository.DeliveryRepository;
import com.webhookreplay.repository.EndpointRepository;
import com.webhookreplay.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Ingest and fan-out — the whole of leg 1.
 *
 * <p>This class is the point of DECISIONS.md #1. The event and every delivery row it fans
 * out to are written in <strong>one transaction</strong>, so there is no window in which an
 * event exists but its deliveries do not, and no dual write between a database and a broker
 * that could half-succeed. With Kafka this method would be the textbook outbox problem;
 * because Postgres is the queue, it is an ordinary commit.
 *
 * <p>Invariant I1 falls out of that: when the transaction commits the caller has not yet
 * been told anything, so a 202 can only be written after the rows are durable.
 */
@Service
public class EventIngestService {

	private final EventRepository eventRepository;

	private final EndpointRepository endpointRepository;

	private final DeliveryRepository deliveryRepository;

	EventIngestService(EventRepository eventRepository, EndpointRepository endpointRepository,
			DeliveryRepository deliveryRepository) {
		this.eventRepository = eventRepository;
		this.endpointRepository = endpointRepository;
		this.deliveryRepository = deliveryRepository;
	}

	/**
	 * @return the id of the persisted event
	 */
	@Transactional
	public UUID ingest(UUID tenantId, String type, String payload) {
		Event event = eventRepository.save(new Event(tenantId, type, payload));

		List<Endpoint> matching = endpointRepository.findMatching(tenantId, type);
		// Zero matches is a successful ingest, not an error. I2 promises an attempt per
		// *active, matching* endpoint, and there may be none — a tenant that has registered
		// nothing yet still gets a 202 and a durable event, which is what makes it possible
		// to register an endpoint afterwards and replay history to it.
		deliveryRepository.saveAll(matching.stream().map(endpoint -> new Delivery(event, endpoint)).toList());

		return event.getId();
	}

}
