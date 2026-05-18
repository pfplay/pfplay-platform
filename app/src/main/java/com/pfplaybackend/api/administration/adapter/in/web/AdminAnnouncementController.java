package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.AdjustScheduleRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.AnnouncementCreateRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AnnouncementSummaryResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementCommandService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 시스템 공지(SystemAnnouncement) admin endpoint — 발행/조회/철회/종료시각 조정/정상종료.
 *
 * <p>5 endpoint:
 * <ul>
 *     <li>{@code POST /api/v1/admin/announcements} — 발행 (201)</li>
 *     <li>{@code GET /api/v1/admin/announcements?page&size} — sentAt DESC 페이지</li>
 *     <li>{@code DELETE /api/v1/admin/announcements/{id}} — 철회 (200)</li>
 *     <li>{@code PATCH /api/v1/admin/announcements/{id}/schedule} — 종료시각 조정 (ACTIVE)</li>
 *     <li>{@code POST /api/v1/admin/announcements/{id}/complete} — 즉시 정상종료 (ACTIVE)</li>
 * </ul>
 *
 * <p>모두 {@code @adminAuth.isAdmin()} 게이팅 — anonymous 401, non-admin 403.
 * Bean Validation 위반은 {@code GlobalExceptionHandler}가 400.
 * AdminContext 주입은 controller-layer ({@code adminContext.currentAdministratorId()})
 * — 패턴 mirror: {@link AdminAvatarCommandController}, {@link AdministratorManagementController}.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §4.1, §4.2, §4.3
 * <br>Spec: docs/superpowers/specs/2026-05-17-announcement-maintenance-lifecycle-design.md
 */
@Tag(name = "Admin Announcement API", description = "시스템 공지 발행/조회/철회/종료시각 조정/정상종료")
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor
@Validated
public class AdminAnnouncementController {

    private static final int MAX_PAGE_SIZE = 200;

    private final SystemAnnouncementCommandService commandService;
    private final SystemAnnouncementRepository repository;
    private final AdminContext adminContext;

    @Operation(summary = "공지 발행",
            description = "type(MAINTENANCE_NOTICE/EVENT/EMERGENCY) + severity + 4개 i18n 텍스트 + 일정. "
                    + "MAINTENANCE_NOTICE는 scheduledStartAt 미래(ANN-005) + scheduled[Start|End]At 모두 필수(ANN-003) "
                    + "+ end>start(ANN-004), expiresAt 금지(ANN-003).")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping
    public ResponseEntity<ApiCommonResponse<Map<String, Long>>> publish(
            @Valid @RequestBody AnnouncementCreateRequest req) {
        Long administratorId = adminContext.currentAdministratorId();
        Long id = commandService.publish(
                req.type(), req.severity(),
                req.titleKo(), req.titleEn(), req.messageKo(), req.messageEn(),
                req.scheduledStartAt(), req.scheduledEndAt(), req.expiresAt(),
                administratorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(Map.of("announcementId", id)));
    }

    @Operation(summary = "공지 history 페이지", description = "sentAt DESC. page>=0, 1<=size<=200.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<Page<AnnouncementSummaryResponse>>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Page<AnnouncementSummaryResponse> result = repository
                .findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt")))
                .map(AnnouncementSummaryResponse::from);
        return ResponseEntity.ok(ApiCommonResponse.success(result));
    }

    @Operation(summary = "공지 철회",
            description = "ANN-001(미존재) 404, ANN-002(이미 철회) 409.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiCommonResponse<Void>> cancel(@PathVariable @Min(1) Long id) {
        Long administratorId = adminContext.currentAdministratorId();
        commandService.cancel(id, administratorId);
        return ResponseEntity.ok(ApiCommonResponse.ok());
    }

    @Operation(summary = "점검 종료시각 조정", description = "ACTIVE 한정. ANN-007(비-ACTIVE) 409, ANN-006(과거시각) 400.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{id}/schedule")
    public ResponseEntity<ApiCommonResponse<Void>> adjustSchedule(
            @PathVariable @Min(1) Long id, @Valid @RequestBody AdjustScheduleRequest req) {
        Long administratorId = adminContext.currentAdministratorId();
        commandService.adjustEndTime(id, req.scheduledEndAt(), administratorId);
        return ResponseEntity.ok(ApiCommonResponse.ok());
    }

    @Operation(summary = "점검 즉시 정상종료", description = "ACTIVE 한정. ANN-008(이미 완료) 409, ANN-007 409.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiCommonResponse<Void>> complete(@PathVariable @Min(1) Long id) {
        Long administratorId = adminContext.currentAdministratorId();
        commandService.complete(id, administratorId);
        return ResponseEntity.ok(ApiCommonResponse.ok());
    }
}
