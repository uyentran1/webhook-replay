package com.webhookreplay.api;

import com.webhookreplay.api.dto.IngestRequest;
import com.webhookreplay.api.dto.IngestResponse;
import com.webhookreplay.service.EventIngestService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Leg 1 of the two legs: sender to us. It ends the moment the rows commit.
 *
 * <p>202 rather than 201: we are not reporting that anything was created at the receiver,
 * only that we have accepted responsibility for delivering it. A 200 would imply the work
 * is done and a 201 would imply a resource the sender can act on; 202 says exactly what is
 * true, which is that this is now our problem and not theirs.
 */
@RestController
@RequestMapping("/v1/events")
class EventIngestController {

	private final EventIngestService eventIngestService;

	EventIngestController(EventIngestService eventIngestService) {
		this.eventIngestService = eventIngestService;
	}

	/**
	 * The tenant comes from the authenticated principal, never from the body or a path
	 * variable. That is the whole isolation story: a sender cannot name a tenant, so it
	 * cannot name someone else's.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	IngestResponse ingest(@AuthenticationPrincipal UUID tenantId, @RequestBody IngestRequest request) {
		return new IngestResponse(
				eventIngestService.ingest(tenantId, request.type(), request.payloadAsJson()));
	}

}
