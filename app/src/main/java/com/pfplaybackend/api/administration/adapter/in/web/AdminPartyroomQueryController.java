package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomQueryService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * B-1 list / B-2 detail 어드민 파티룸 조회 controller.
 *
 * <p>모든 endpoint는 {@code @adminAuth.isAdmin()}로 게이팅된다. {@code list}는 페이징/필터/정렬을
 * 지원하며 admin DOS 방어를 위해 {@code size}를 200으로 캡한다. 정렬 화이트리스트 위반은
 * 컨트롤러에서 400 BadRequest로 로컬 변환된다(글로벌 IAE 핸들러를 추가하지 않아 다른 IAE 경로
 * 흡수를 방지). {@code detail}은 단일 id 경로이며, NOT_FOUND_ROOM 도메인 예외는
 * {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}가 404로 매핑한다.
 */
@Tag(name = "Admin Partyroom Queries API", description = "B-1 list / B-2 detail")
@RestController
@RequestMapping("/api/v1/admin/partyrooms")
@RequiredArgsConstructor
public class AdminPartyroomQueryController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AdminPartyroomQueryService queryService;

    @Operation(summary = "B-1 룸 목록 (페이징/필터/정렬)")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<Page<AdminPartyroomListItemResponse>>> list(
            @RequestParam(required = false) PartyroomStatus status,
            @RequestParam(required = false) StageType stageType,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String host,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        // size 200 cap (admin DOS 방어)
        Pageable bounded = pageable.getPageSize() > MAX_PAGE_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;
        try {
            return ResponseEntity.ok(ApiCommonResponse.success(queryService.list(
                    new AdminPartyroomListFilter(status, stageType, createdFrom, createdTo, host),
                    bounded)));
        } catch (IllegalArgumentException e) {
            // AdminPartyroomQueryRepositoryImpl.applySort throws IAE for unsupported sort fields.
            // Translate locally to 400 instead of letting it propagate to the generic 500 handler.
            throw new BadRequestException("ADM-PR-001", "Unsupported sort field: " + e.getMessage());
        }
    }

    @Operation(summary = "B-2 룸 상세")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{partyroomId}")
    public ResponseEntity<ApiCommonResponse<AdminPartyroomDetailResponse>> detail(@PathVariable Long partyroomId) {
        return ResponseEntity.ok(ApiCommonResponse.success(queryService.detail(new PartyroomId(partyroomId))));
    }
}
