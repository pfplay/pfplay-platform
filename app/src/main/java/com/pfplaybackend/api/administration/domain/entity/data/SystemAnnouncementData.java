package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.exception.AnnouncementException;
import com.pfplaybackend.api.administration.domain.value.*;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;

@Entity
@Table(name = "system_announcement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemAnnouncementData extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 32) private AnnouncementType type;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16) private AnnouncementSeverity severity;
    @Column(name = "title_ko", nullable = false, length = 200) private String titleKo;
    @Column(name = "title_en", nullable = false, length = 200) private String titleEn;
    @Column(name = "message_ko", nullable = false, length = 2000) private String messageKo;
    @Column(name = "message_en", nullable = false, length = 2000) private String messageEn;
    @Column(name = "scheduled_start_at") private LocalDateTime scheduledStartAt;
    @Column(name = "scheduled_end_at") private LocalDateTime scheduledEndAt;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(name = "sent_at", nullable = false) private LocalDateTime sentAt;
    @Column(name = "sent_by_administrator_id", nullable = false) private Long sentByAdministratorId;
    @Column(name = "maintenance_started_at") private LocalDateTime maintenanceStartedAt;
    @Column(name = "cancelled_at") private LocalDateTime cancelledAt;
    @Column(name = "cancelled_by_administrator_id") private Long cancelledByAdministratorId;

    public static SystemAnnouncementData create(
            AnnouncementType type, AnnouncementSeverity severity,
            String titleKo, String titleEn, String messageKo, String messageEn,
            LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt, LocalDateTime expiresAt,
            LocalDateTime sentAt, Long sentByAdministratorId) {
        if (type == AnnouncementType.MAINTENANCE_NOTICE) {
            if (scheduledStartAt == null || scheduledEndAt == null || expiresAt != null)
                throw ExceptionCreator.create(AnnouncementException.INVALID_SCHEDULE_FOR_TYPE);
            if (!scheduledEndAt.isAfter(scheduledStartAt))
                throw ExceptionCreator.create(AnnouncementException.INVALID_SCHEDULE_WINDOW);
        } else {
            if (scheduledStartAt != null || scheduledEndAt != null)
                throw ExceptionCreator.create(AnnouncementException.INVALID_SCHEDULE_FOR_TYPE);
        }
        SystemAnnouncementData e = new SystemAnnouncementData();
        e.type = type; e.severity = severity;
        e.titleKo = titleKo; e.titleEn = titleEn; e.messageKo = messageKo; e.messageEn = messageEn;
        e.scheduledStartAt = scheduledStartAt; e.scheduledEndAt = scheduledEndAt; e.expiresAt = expiresAt;
        e.sentAt = sentAt; e.sentByAdministratorId = sentByAdministratorId;
        return e;
    }

    public void markMaintenanceStarted(Clock clock) {
        if (type != AnnouncementType.MAINTENANCE_NOTICE)
            throw new IllegalStateException("non-MAINTENANCE_NOTICE: " + type);
        if (maintenanceStartedAt != null) throw new IllegalStateException("already started: " + id);
        if (cancelledAt != null) throw new IllegalStateException("cancelled: " + id);
        this.maintenanceStartedAt = LocalDateTime.now(clock);
    }

    public void cancel(Long administratorId, Clock clock) {
        if (cancelledAt != null) throw ExceptionCreator.create(AnnouncementException.ALREADY_CANCELLED);
        this.cancelledAt = LocalDateTime.now(clock);
        this.cancelledByAdministratorId = administratorId;
    }

    public boolean isMaintenancePhaseActive() {
        return type == AnnouncementType.MAINTENANCE_NOTICE && maintenanceStartedAt != null && cancelledAt == null;
    }
}
