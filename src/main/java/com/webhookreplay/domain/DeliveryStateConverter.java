package com.webhookreplay.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

/**
 * Java constants are {@code IN_FLIGHT}; the column holds {@code in_flight}.
 *
 * <p>Worth being explicit about rather than papering over: the enum name and the stored
 * value are separate vocabularies. {@code @Enumerated(STRING)} would write {@code IN_FLIGHT}
 * and immediately violate the {@code delivery_state_valid} CHECK, which is the constraint
 * doing its job. The stored form is lowercase because it is also the wire form — DESIGN.md
 * §4 writes the states lowercase and the API returns them unchanged.
 *
 * <p>{@code autoApply} means every {@link DeliveryState} field is converted without each
 * one having to remember {@code @Convert}.
 */
@Converter(autoApply = true)
public class DeliveryStateConverter implements AttributeConverter<DeliveryState, String> {

	@Override
	public String convertToDatabaseColumn(DeliveryState state) {
		return state == null ? null : state.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public DeliveryState convertToEntityAttribute(String column) {
		return column == null ? null : DeliveryState.valueOf(column.toUpperCase(Locale.ROOT));
	}

}
