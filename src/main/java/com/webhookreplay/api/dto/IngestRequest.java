package com.webhookreplay.api.dto;

// Jackson 3 (Boot 4) moved the base package from com.fasterxml.jackson to tools.jackson.
// Only the annotations kept the old coordinates, which is why both appear on the classpath.
import tools.jackson.databind.JsonNode;

/**
 * The ingest envelope: what kind of thing happened, and the opaque body describing it.
 *
 * <p>{@code payload} is bound as a {@link JsonNode} rather than a {@code String} so that
 * malformed JSON is rejected by the parser as a 400 instead of being stored and only failing
 * in week 12, the first time someone runs {@code payload ->> '...'} against it.
 *
 * <p>Validated here rather than with {@code @NotBlank}: bean validation is not on the
 * classpath, and two fields do not justify adding a starter for it. Jackson turns the
 * exception from a compact constructor into a 400, which is the status these deserve anyway.
 */
public record IngestRequest(String type, JsonNode payload) {

	public IngestRequest {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("'type' is required");
		}
		if (payload == null || payload.isNull()) {
			throw new IllegalArgumentException("'payload' is required");
		}
		// An endpoint's event_types filter matches on this string, so a stray trailing space
		// in the sender's JSON would silently route to nothing.
		type = type.trim();
	}

}
