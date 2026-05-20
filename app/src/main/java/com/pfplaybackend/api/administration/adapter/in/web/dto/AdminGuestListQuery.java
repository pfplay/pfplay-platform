package com.pfplaybackend.api.administration.adapter.in.web.dto;

import java.time.LocalDate;

/**
 * GET /admin/guests query parameters. tier 필드 부재 (guest 는 항상 GT — Spec §3, §6.1).
 *
 * <p>sort 허용 값은 AdminMemberListQuery 와 동일 상수값을 의도적으로 공유 (UI 정렬 옵션 통일).
 * size cap 은 Controller validation.
 */
public record AdminGuestListQuery(
        String email,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        String sort
) {
    public static final String SORT_CREATED_AT_DESC = "created_at_desc";
    public static final String SORT_CREATED_AT_ASC = "created_at_asc";
    public static final String SORT_LAST_ACTIVITY_DESC = "last_activity_desc";
}
