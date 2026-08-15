package com.webhookreplay.api.dto;

// Jackson 3 (Boot 4) moved the base package from com.fasterxml.jackson to tools.jackson.
// Only the annotations kept the old coordinates, which is why both appear on the classpath.
import tools.jackson.databind.JsonNode;

/**
 * The ingest envelope: what kind of thing happened, and the opaque body describing it.
 *
 * <p>{@code payload} is bound as a {@link JsonNode} rather than a {@code String} so malformed
 * JSON is rejected by the parser as a 400, instead of being stored and only failing in week
 * 12 the first time someone runs {@code payload ->> '...'} against it.
 *
 * <p>Validated here rather than with {@code @NotBlank}: bean validation is not on the
 * classpath, and these fields do not justify adding a starter for it. Jackson turns the
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
		// Binding to JsonNode alone does NOT give the guarantee the javadoc above wants:
		// `"payload": "just a string"` parses cleanly and stores a jsonb *scalar*, against
		// which `payload ->> 'order_id'` returns nothing — exactly the week-12 failure this
		// was supposed to prevent. The contract is an object, so say so.
		if (!payload.isObject()) {
			throw new IllegalArgumentException("'payload' must be a JSON object");
		}
		// Rejected here, not at the database. Jackson parses an escaped NUL inside a string
		// quite happily and re-emits the escape, but jsonb refuses it ("unsupported Unicode
		// escape sequence") — so without this, sender-controlled input produces a 500 and a
		// rolled-back transaction instead of a 400. Escaped NULs are not exotic: anything
		// relaying a database text column can carry one.
		//
		// Note the doubled backslash: it is matching the two characters a backslash and a
		// 'u', not a NUL character. A bare escape here would be processed by javac even in
		// a comment.
		//
		// In the constructor rather than in payloadAsJson() because only exceptions thrown
		// during deserialisation get wrapped into a 400; the same throw from a controller
		// method body would surface as a 500.
		if (payload.toString().contains("\\u0000")) {
			throw new IllegalArgumentException("'payload' must not contain \\u0000");
		}
		// An endpoint's event_types filter matches on this string, so a stray trailing space
		// in the sender's JSON would silently route to nothing.
		type = type.trim();
	}

	/**
	 * The payload as the JSON text we store, sign in week 7, and send.
	 *
	 * <p>Not byte-identical to what the sender wrote, and it does not need to be — the
	 * {@code jsonb} column normalises key order and whitespace regardless. See
	 * {@code Event.payload}.
	 */
	public String payloadAsJson() {
		return payload.toString();
	}

}
