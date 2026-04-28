package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberSummaryResponse;
import com.pfplaybackend.api.administration.application.service.AdminMemberQueryService;
import com.pfplaybackend.api.administration.domain.exception.AdminMemberException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * A-1 list / A-2 detail 어드민 멤버 조회 controller.
 *
 * <p>모든 endpoint는 {@code @adminAuth.isAdmin()}로 게이팅된다. {@code MEMBER_NOT_FOUND}
 * 도메인 예외는 {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}가
 * 404로, {@code INVALID_LIST_QUERY}는 400으로 매핑한다.
 *
 * <p>A-1 (list)은 {@link AdminMemberListQuery}를 사용하며 size cap(200) / 가입일 range /
 * sort enum을 controller layer에서 검증한다 ({@link AdminMemberException#INVALID_LIST_QUERY}).
 * Spring MVC의 자동 enum/date 바인딩 실패는 {@code GlobalExceptionHandler}가 400으로 매핑한다.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.1, §3.2
 */
@Tag(name = "Admin Member Queries API", description = "A-1 list / A-2 detail")
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberQueryController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AdminMemberQueryService adminMemberQueryService;

    @Operation(summary = "A-1 멤버 목록 — filter/sort/pagination")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<Page<AdminMemberSummaryResponse>>> getList(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) AuthorityTier tier,
            @RequestParam(name = "joined_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(name = "joined_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = AdminMemberListQuery.SORT_CREATED_AT_DESC) String sort
    ) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
        }
        if (page < 0) {
            throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
        }
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
        }
        if (!isValidSort(sort)) {
            throw ExceptionCreator.create(AdminMemberException.INVALID_LIST_QUERY);
        }

        AdminMemberListQuery query = new AdminMemberListQuery(email, tier, joinedFrom, joinedTo, sort);
        Pageable pageable = PageRequest.of(page, size);
        Page<AdminMemberSummaryResponse> result = adminMemberQueryService.getList(query, pageable);
        return ResponseEntity.ok(ApiCommonResponse.success(result));
    }

    @Operation(summary = "A-2 멤버 상세 — recentActivityLog top 30")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiCommonResponse<AdminMemberDetailResponse>> getDetail(
            @PathVariable Long memberId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminMemberQueryService.getDetail(memberId)));
    }

    private static boolean isValidSort(String sort) {
        return AdminMemberListQuery.SORT_CREATED_AT_DESC.equals(sort)
                || AdminMemberListQuery.SORT_CREATED_AT_ASC.equals(sort)
                || AdminMemberListQuery.SORT_LAST_ACTIVITY_DESC.equals(sort);
    }
}
