package com.pfplaybackend.api.administration.adapter.out.edge;

import com.pfplaybackend.api.administration.adapter.out.edge.properties.VercelEdgeConfigProperties;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VercelEdgeConfigAdapter implements EdgeConfigPort {

    private final RestTemplate restTemplate;
    private final VercelEdgeConfigProperties properties;

    @Override
    public void writeMaintenance(SystemAnnouncementData entity, MaintenancePhase phase) {
        if (properties.getId() == null || properties.getId().isBlank()) {
            log.warn("[EdgeConfig] VERCEL_EDGE_CONFIG_ID not set — skip write.");
            return;
        }
        String url = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl())
            .pathSegment("v1", "edge-config", properties.getId(), "items")
            .queryParamIfPresent("teamId",
                properties.getTeamId() != null && !properties.getTeamId().isBlank()
                    ? Optional.of(properties.getTeamId()) : Optional.<String>empty())
            .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Object value = entity == null ? null : Map.of(
            "phase", phase.name(),
            "startAt", entity.getScheduledStartAt().toString(),
            "endAt", entity.getScheduledEndAt().toString(),
            "messageKo", entity.getMessageKo(),
            "messageEn", entity.getMessageEn());

        // Map.of() rejects null values, but Edge Config API requires {"value": null} to delete
        // a key. Use a LinkedHashMap so the JSON serializer emits "value": null properly.
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("operation", "upsert");
        item.put("key", properties.getMaintenanceKey());
        item.put("value", value);
        Map<String, Object> body = Map.of("items", List.of(item));

        try {
            restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers), Void.class);
        } catch (RestClientException e) {
            log.error("[EdgeConfig] write failed (no retry — scheduler will re-attempt)", e);
            throw new RuntimeException("Vercel Edge Config write failed: " + e.getMessage(), e);
        }
    }
}
