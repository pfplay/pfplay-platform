package com.pfplaybackend.api.administration.domain.value;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * JsonMetadata ↔ JSON String JPA Converter.
 *
 * - empty/null → DB NULL
 * - 직렬화 실패 시 IllegalStateException (보존 필수 데이터라 swallow 금지)
 */
@Converter(autoApply = false)
public class JsonMetadataConverter implements AttributeConverter<JsonMetadata, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(JsonMetadata attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute.data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JsonMetadata", e);
        }
    }

    @Override
    public JsonMetadata convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return JsonMetadata.empty();
        try {
            return JsonMetadata.of(MAPPER.readValue(dbData, MAP_TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JsonMetadata: " + dbData, e);
        }
    }
}
