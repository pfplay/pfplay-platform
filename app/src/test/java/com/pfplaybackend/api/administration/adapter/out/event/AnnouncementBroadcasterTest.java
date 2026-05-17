package com.pfplaybackend.api.administration.adapter.out.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementBroadcasterTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock EdgeConfigPort edgeConfigPort;
    @InjectMocks AnnouncementBroadcaster broadcaster;

    @Test
    void maintenanceEnded_broadcastsDismissAndDeletesEdgeConfig() {
        ZoneId z = ZoneId.of("Asia/Seoul");
        Clock clock = Clock.fixed(LocalDateTime.of(2026,5,4,3,0).atZone(z).toInstant(), z);
        SystemAnnouncementData e = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "점검","m","b","b",
                LocalDateTime.of(2026,5,4,3,0), LocalDateTime.of(2026,5,4,4,0),
                null, LocalDateTime.of(2026,5,4,2,0), 1L);
        e.markMaintenanceStarted(clock);
        e.markCompleted(Clock.fixed(LocalDateTime.of(2026,5,4,4,0).atZone(z).toInstant(), z));

        broadcaster.on(new MaintenanceEndedEvent(e));

        ArgumentCaptor<Map<String,Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/sub/system/announcements"), cap.capture());
        assertThat(cap.getValue().get("eventType")).isEqualTo("MAINTENANCE_ENDED");
        verify(edgeConfigPort).writeMaintenance(null, null);
    }
}
