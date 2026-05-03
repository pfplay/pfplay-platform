package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;

import java.time.LocalDate;

/**
 * A-1 list query parameters. Controller에서 Spring binding.
 *
 * <p>sort 허용 값: {@code created_at_desc}(default), {@code created_at_asc},
 * {@code last_activity_desc}. size cap 200 (Controller validation).
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §3.1
 */
public record AdminMemberListQuery(
        String email,
        AuthorityTier tier,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        String sort
) {
    public static final String SORT_CREATED_AT_DESC = "created_at_desc";
    public static final String SORT_CREATED_AT_ASC = "created_at_asc";
    public static final String SORT_LAST_ACTIVITY_DESC = "last_activity_desc";
}
