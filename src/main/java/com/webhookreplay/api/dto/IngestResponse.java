package com.webhookreplay.api.dto;

import java.util.UUID;

/**
 * Serialised as {@code {"event_id": "..."}} — see the snake_case naming strategy in
 * {@code application.properties}.
 *
 * <p>Deliberately just the id. The sender is told what to quote when asking about this
 * event later, and nothing about delivery: at 202 the deliveries exist but none has been
 * attempted, so any status reported here would be stale before the response was written.
 * That question is answered by {@code GET /v1/events/{id}}.
 */
public record IngestResponse(UUID eventId) {
}
