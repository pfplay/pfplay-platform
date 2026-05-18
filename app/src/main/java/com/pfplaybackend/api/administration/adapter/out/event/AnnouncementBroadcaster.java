package com.pfplaybackend.api.administration.adapter.out.event;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.AnnouncementCancelledEvent;
import com.pfplaybackend.api.administration.domain.event.AnnouncementPublishedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementBroadcaster {

    // 기존 realtime/WebSocketConfig 가 enableSimpleBroker("/sub") 만 등록하므로
    // 새 토픽도 /sub prefix 를 따른다 (partyroom 토픽 컨벤션과 일치).
    // /topic prefix 는 broker 에 미등록 → silently drop 됨.
    private static final String TOPIC = "/sub/system/announcements";

    private final SimpMessagingTemplate messagingTemplate;
    private final EdgeConfigPort edgeConfigPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AnnouncementPublishedEvent event) {
        SystemAnnouncementData e = event.entity();
        broadcast("ANNOUNCEMENT_PUBLISHED", e, null);
        if (e.getType() == AnnouncementType.MAINTENANCE_NOTICE) {
            tryWriteEdgeConfig(e, MaintenancePhase.PLANNED);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AnnouncementCancelledEvent event) {
        SystemAnnouncementData e = event.entity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ANNOUNCEMENT_CANCELLED");
        payload.put("announcementId", e.getId());
        payload.put("cancelledAt", e.getCancelledAt());
        messagingTemplate.convertAndSend(TOPIC, payload);
        // 점검이 ACTIVE 였으면 Edge Config maintenance 키도 종료
        if (e.getType() == AnnouncementType.MAINTENANCE_NOTICE && e.getMaintenanceStartedAt() != null) {
            tryWriteEdgeConfig(null, null);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MaintenanceStartedEvent event) {
        broadcast("MAINTENANCE_STARTED", event.entity(), null);
        tryWriteEdgeConfig(event.entity(), MaintenancePhase.ACTIVE);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MaintenanceEndedEvent event) {
        SystemAnnouncementData e = event.entity();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "MAINTENANCE_ENDED");
        payload.put("announcementId", e.getId());
        payload.put("completedAt", e.getCompletedAt());
        messagingTemplate.convertAndSend(TOPIC, payload);
        tryWriteEdgeConfig(null, null);
    }

    private void broadcast(String eventType, SystemAnnouncementData e, Object extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("announcementId", e.getId());
        payload.put("type", e.getType().name());
        payload.put("severity", e.getSeverity().name());
        payload.put("titleKo", e.getTitleKo());
        payload.put("titleEn", e.getTitleEn());
        payload.put("messageKo", e.getMessageKo());
        payload.put("messageEn", e.getMessageEn());
        payload.put("scheduledStartAt", e.getScheduledStartAt());
        payload.put("scheduledEndAt", e.getScheduledEndAt());
        payload.put("expiresAt", e.getExpiresAt());
        payload.put("sentAt", e.getSentAt());
        messagingTemplate.convertAndSend(TOPIC, payload);
    }

    private void tryWriteEdgeConfig(SystemAnnouncementData entity, MaintenancePhase phase) {
        try {
            edgeConfigPort.writeMaintenance(entity, phase);
        } catch (RuntimeException ex) {
            log.error("[Announcement] Edge Config write failed — DB state authoritative, " +
                      "scheduler will re-attempt next tick.", ex);
        }
    }
}
