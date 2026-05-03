package com.pfplaybackend.api.administration.domain.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMetadataConverterTest {

    private final JsonMetadataConverter converter = new JsonMetadataConverter();

    @Test
    @DisplayName("convertToDatabaseColumn — empty/null은 DB NULL로 저장")
    void emptyToNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(JsonMetadata.empty())).isNull();
    }

    @Test
    @DisplayName("convertToDatabaseColumn — Map → JSON 문자열")
    void mapToJson() {
        JsonMetadata meta = JsonMetadata.of(Map.of("flag", "FEATURED", "old", "NORMAL"));
        String json = converter.convertToDatabaseColumn(meta);
        assertThat(json).contains("\"flag\":\"FEATURED\"").contains("\"old\":\"NORMAL\"");
    }

    @Test
    @DisplayName("convertToEntityAttribute — null/blank → empty")
    void nullToEmpty() {
        assertThat(converter.convertToEntityAttribute(null).isEmpty()).isTrue();
        assertThat(converter.convertToEntityAttribute("  ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("convertToEntityAttribute — JSON 문자열 → Map")
    void jsonToMap() {
        JsonMetadata meta = converter.convertToEntityAttribute("{\"flag\":\"FEATURED\",\"x\":42}");
        assertThat(meta.data()).containsEntry("flag", "FEATURED").containsEntry("x", 42);
    }

    @Test
    @DisplayName("round-trip — Map → JSON → Map 동일성")
    void roundTrip() {
        Map<String, Object> original = Map.of("a", "1", "b", 2);
        String json = converter.convertToDatabaseColumn(JsonMetadata.of(original));
        JsonMetadata back = converter.convertToEntityAttribute(json);
        assertThat(back.data()).isEqualTo(original);
    }
}
