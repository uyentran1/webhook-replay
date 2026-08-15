package com.webhookreplay.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

/**
 * See {@link DeliveryStateConverter} for why this exists rather than
 * {@code @Enumerated(STRING)}.
 */
@Converter(autoApply = true)
public class EndpointStateConverter implements AttributeConverter<EndpointState, String> {

	@Override
	public String convertToDatabaseColumn(EndpointState state) {
		return state == null ? null : state.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public EndpointState convertToEntityAttribute(String column) {
		return column == null ? null : EndpointState.valueOf(column.toUpperCase(Locale.ROOT));
	}

}
