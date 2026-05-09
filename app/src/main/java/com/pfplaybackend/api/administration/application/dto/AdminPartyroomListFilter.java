package com.pfplaybackend.api.administration.application.dto;

import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;

/**
 * Filter parameters for {@code AdminPartyroomQueryRepository.findAdminList}.
 *
 * Each field is nullable; null means "no constraint" except for {@link #status},
 * where null means the default behavior of excluding TERMINATED rooms (i.e.
 * ACTIVE + SUSPENDED only). See {@code AdminPartyroomQueryRepositoryImpl}.
 */
public record AdminPartyroomListFilter(
        PartyroomStatus status,
        StageType stageType,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        String hostQuery
) {}
