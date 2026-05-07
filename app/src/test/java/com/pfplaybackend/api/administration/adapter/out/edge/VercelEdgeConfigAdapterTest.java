package com.pfplaybackend.api.administration.adapter.out.edge;

import com.pfplaybackend.api.administration.adapter.out.edge.properties.VercelEdgeConfigProperties;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VercelEdgeConfigAdapterTest {
    private RestTemplate rt;
    private VercelEdgeConfigProperties props;
    private VercelEdgeConfigAdapter adapter;

    @BeforeEach void setUp() {
        rt = mock(RestTemplate.class);
        props = new VercelEdgeConfigProperties();
        props.setId("ecfg_test"); props.setApiToken("token");
        adapter = new VercelEdgeConfigAdapter(rt, props);
    }

    @Test
    @DisplayName("write success — single PATCH")
    void success() {
        when(rt.exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());
        adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED);
        verify(rt, times(1)).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("write null entity — maintenance 키 null 로 set")
    void writeNull() {
        when(rt.exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());
        adapter.writeMaintenance(null, null);
        verify(rt, times(1)).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("API 실패 → RuntimeException (재시도 없음)")
    void failNoRetry() {
        when(rt.exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenThrow(new RestClientException("timeout"));
        assertThatThrownBy(() -> adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED))
            .isInstanceOf(RuntimeException.class);
        verify(rt, times(1)).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("teamId 설정 시 query param 추가")
    void teamId() {
        props.setTeamId("team_xyz");
        when(rt.exchange(contains("teamId=team_xyz"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());
        adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED);
        verify(rt, times(1)).exchange(contains("teamId=team_xyz"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("id blank 면 skip (warn log)")
    void skipWhenIdBlank() {
        props.setId("");
        adapter.writeMaintenance(maintenance(), MaintenancePhase.PLANNED);
        verify(rt, never()).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(Void.class));
    }

    private SystemAnnouncementData maintenance() {
        LocalDateTime s = LocalDateTime.parse("2026-05-04T03:00:00");
        return SystemAnnouncementData.create(
            AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
            "점검", "M", "안내", "N", s, s.plusHours(1), null, s.minusDays(1), 1L);
    }
}
