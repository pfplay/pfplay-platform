package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.enums.AuthorityTier;

/**
 * A-3 PATCH /admin/members/{id}/tier response body.
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md §3.1
 */
public record AdminMemberTierChangeResponse(
        Long memberId,
        AuthorityTier oldTier,
        AuthorityTier newTier
) {}
