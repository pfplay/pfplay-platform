package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AnnouncementSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse.MaintenanceInfo;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Anonymous {@code /api/v1/system/status} backing query service.
 *
 * <p>3 repository call composition:
 * <ul>
 *     <li>{@link SystemAnnouncementRepository#findCurrentMaintenance()} — ACTIVE 점검 (0/1)</li>
 *     <li>{@link SystemAnnouncementRepository#findActivePublic(LocalDateTime)} — EVENT/EMERGENCY</li>
 *     <li>{@link SystemAnnouncementRepository#findPlannedMaintenance(LocalDateTime)} — 예정 점검</li>
 * </ul>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §4.4
 */
@Service
@RequiredArgsConstructor
public class SystemAnnouncementQueryService {

    private final SystemAnnouncementRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SystemStatusResponse getSystemStatus() {
        LocalDateTime now = LocalDateTime.now(clock);
        MaintenanceInfo current = repository.findCurrentMaintenance()
                .map(e -> toInfo(e, MaintenancePhase.ACTIVE))
                .orElse(null);
        List<AnnouncementSummaryResponse> active = repository.findActivePublic(now).stream()
                .map(AnnouncementSummaryResponse::from)
                .toList();
        List<MaintenanceInfo> planned = repository.findPlannedMaintenance(now).stream()
                .map(e -> toInfo(e, MaintenancePhase.PLANNED))
                .toList();
        return new SystemStatusResponse(current, active, planned);
    }

    private MaintenanceInfo toInfo(SystemAnnouncementData e, MaintenancePhase phase) {
        return new MaintenanceInfo(
                phase.name(),
                e.getScheduledStartAt(),
                e.getScheduledEndAt(),
                e.getMessageKo(),
                e.getMessageEn());
    }
}
