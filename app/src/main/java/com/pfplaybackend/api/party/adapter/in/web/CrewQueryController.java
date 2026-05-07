package com.pfplaybackend.api.party.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.party.application.dto.result.CrewProfileSummaryResult;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Crew API")
@RequestMapping("/api/v1/crews")
@RestController
@RequiredArgsConstructor
public class CrewQueryController {

    private final PartyroomQueryService partyroomQueryService;

    @Operation(summary = "크루 프로필 조회", description = "특정 크루의 프로필 요약 정보를 조회합니다. 게스트/멤버 모두 조회 가능합니다.")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({CrewException.class})
    @GetMapping("/{crewId}/profile/summary")
    @PreAuthorize("hasAnyRole('GUEST', 'MEMBER')")
    public ResponseEntity<ApiCommonResponse<CrewProfileSummaryResult>> getOtherProfileSummary(
            @Parameter(description = "크루 ID") @PathVariable Long crewId) {
        return ResponseEntity.ok().body(ApiCommonResponse.success(partyroomQueryService.getProfileSummaryByCrewId(crewId)));
    }
}
