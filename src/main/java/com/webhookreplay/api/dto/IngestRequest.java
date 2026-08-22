package com.webhookreplay.api.dto;

import tools.jackson.databind.JsonNode;

public record IngestRequest(String type, JsonNode payload) {

	public IngestRequest {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("'type' is required");
		}
		if (payload == null || payload.isNull()) {
			throw new IllegalArgumentException("'payload' is required");
		}
		if (!payload.isObject()) {
			throw new IllegalArgumentException("'payload' must be a JSON object");
		}
		if (payload.toString().contains("\\u0000")) {
			throw new IllegalArgumentException("'payload' must not contain \\u0000");
		}

		type = type.trim();
	}

	public String payloadAsJson() {
		return payload.toString();
	}

}
