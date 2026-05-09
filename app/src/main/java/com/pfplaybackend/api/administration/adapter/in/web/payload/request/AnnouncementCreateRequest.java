package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * POST /api/v1/admin/announcements 요청 페이로드.
 *
 * <p>type/severity는 필수, 4개 i18n 텍스트는 NotBlank + Size(max).
 * scheduled* 는 nullable — type별 invariant는
 * {@link com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData#create}
 * 팩토리(ANN-003/004)와 service-layer(ANN-005)에서 enforce.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §4.1
 */
public record AnnouncementCreateRequest(
        @NotNull AnnouncementType type,
        @NotNull AnnouncementSeverity severity,
        @NotBlank @Size(max = 200) String titleKo,
        @NotBlank @Size(max = 200) String titleEn,
        @NotBlank @Size(max = 2000) String messageKo,
        @NotBlank @Size(max = 2000) String messageEn,
        LocalDateTime scheduledStartAt,
        LocalDateTime scheduledEndAt,
        LocalDateTime expiresAt
) {}
