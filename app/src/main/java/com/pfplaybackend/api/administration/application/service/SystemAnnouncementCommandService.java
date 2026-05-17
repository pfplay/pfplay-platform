package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.AnnouncementCancelledEvent;
import com.pfplaybackend.api.administration.domain.event.AnnouncementPublishedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.exception.AnnouncementException;
import com.pfplaybackend.api.administration.domain.port.EdgeConfigPort;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 시스템 공지(SystemAnnouncement) 발행/취소 command service.
 *
 * <p>책임:
 * <ul>
 *     <li>{@link #publish}: 공지 발행 — MAINTENANCE_NOTICE 의 scheduledStartAt 미래 검증
 *         (ANN-005) 후 {@link SystemAnnouncementData#create} 팩토리에 위임 (ANN-003/004 invariants).
 *         persist 후 {@link AnnouncementPublishedEvent} 발행 → AFTER_COMMIT listener 가
 *         WS broadcast + Edge Config write.</li>
 *     <li>{@link #cancel}: 철회 — entity 부재 시 ANN-001, 도메인이 already-cancelled 시 ANN-002.
 *         성공 시 {@link AnnouncementCancelledEvent} 발행.</li>
 * </ul>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §5.3
 */
@Service
@RequiredArgsConstructor
public class SystemAnnouncementCommandService {

    private final SystemAnnouncementRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final EdgeConfigPort edgeConfigPort;

    @Transactional
    public Long publish(AnnouncementType type, AnnouncementSeverity severity,
                        String titleKo, String titleEn, String messageKo, String messageEn,
                        LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt, LocalDateTime expiresAt,
                        Long administratorId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (type == AnnouncementType.MAINTENANCE_NOTICE
                && scheduledStartAt != null
                && !scheduledStartAt.isAfter(now)) {
            throw ExceptionCreator.create(AnnouncementException.SCHEDULED_START_IN_PAST);
        }
        SystemAnnouncementData entity = SystemAnnouncementData.create(
                type, severity, titleKo, titleEn, messageKo, messageEn,
                scheduledStartAt, scheduledEndAt, expiresAt, now, administratorId);
        SystemAnnouncementData saved = repository.save(entity);
        eventPublisher.publishEvent(new AnnouncementPublishedEvent(saved));
        return saved.getId();
    }

    @Transactional
    public void cancel(Long id, Long administratorId) {
        SystemAnnouncementData entity = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND));
        entity.cancel(administratorId, clock);
        eventPublisher.publishEvent(new AnnouncementCancelledEvent(entity));
    }

    @Transactional
    public void complete(Long id, Long administratorId) {
        SystemAnnouncementData entity = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND));
        entity.markCompleted(clock);
        eventPublisher.publishEvent(new MaintenanceEndedEvent(entity));
    }

    @Transactional
    public void adjustEndTime(Long id, LocalDateTime newEnd, Long administratorId) {
        SystemAnnouncementData entity = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(AnnouncementException.ANNOUNCEMENT_NOT_FOUND));
        entity.adjustScheduledEndTime(newEnd, clock);
        edgeConfigPort.writeMaintenance(entity, MaintenancePhase.ACTIVE);
    }
}
