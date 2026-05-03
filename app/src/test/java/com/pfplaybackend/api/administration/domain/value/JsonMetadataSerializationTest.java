package com.pfplaybackend.api.administration.domain.value;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for {@code admin-backend-asks.md} A7.
 *
 * <p>{@code JsonMetadata} is a typed wrapper around a {@code Map<String, Object>} JSON
 * payload persisted on {@code partyroom_admin_action.metadata} and surfaced through
 * {@code RecentActivityLogItem.metadata} (member detail) and
 * {@code AdminPartyroomDetailResponse.AdminActionSummary.metadata} (partyroom detail).
 * The class exposes the underlying map only via the record-style accessor
 * {@code data()}, which Jackson's default property discovery does NOT pick up. Without
 * intervention, responses serialize {@code JsonMetadata} as
 * {@code {"empty": false}} (the {@code is*} pattern catches {@code isEmpty()}), losing
 * every actual metadata field — actor_type, provider, old_tier, new_tier,
 * crew_penalty_history_id, etc.
 *
 * <p>The fix is the {@code @JsonValue} annotation on {@code data()}: Jackson serializes
 * the wrapper as the underlying map directly. If anyone removes that annotation in a
 * future refactor, this test fails immediately.
 */
class JsonMetadataSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialize_populated_metadata_unwrapsKeysAndValues() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actor_type", "ADMINISTRATOR");
        data.put("provider", "LOCAL");
        data.put("old_tier", "AM");
        data.put("new_tier", "FM");
        JsonMetadata metadata = JsonMetadata.of(data);

        String json = mapper.writeValueAsString(metadata);

        // Underlying map keys/values must appear at the wrapper's level.
        assertThat(json).contains("\"actor_type\":\"ADMINISTRATOR\"");
        assertThat(json).contains("\"provider\":\"LOCAL\"");
        assertThat(json).contains("\"old_tier\":\"AM\"");
        assertThat(json).contains("\"new_tier\":\"FM\"");
        // The pre-fix leak — {"empty": false} — must NOT be in the output.
        assertThat(json).doesNotContain("\"empty\"");
        // The wrapper class must NOT introduce a "data" envelope around its payload.
        assertThat(json).doesNotContain("\"data\"");
    }

    @Test
    void serialize_empty_metadata_yieldsEmptyObject() throws Exception {
        String json = mapper.writeValueAsString(JsonMetadata.empty());

        // Empty wrapper serializes as an empty JSON object, not as {"empty": true}.
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void serialize_inside_holder_record_unwrapsTransparently() throws Exception {
        Holder holder = new Holder("SIGNED_IN",
                JsonMetadata.of(Map.of("actor_type", "ADMINISTRATOR", "provider", "LOCAL")));

        String json = mapper.writeValueAsString(holder);

        // metadata field's value must be the unwrapped map, mirroring the response shape
        // RecentActivityLogItem and AdminActionSummary expose to admin clients.
        assertThat(json).contains("\"metadata\":{");
        assertThat(json).contains("\"actor_type\":\"ADMINISTRATOR\"");
        assertThat(json).doesNotContain("\"empty\"");
    }

    private record Holder(String eventType, JsonMetadata metadata) {}
}
