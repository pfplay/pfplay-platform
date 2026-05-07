package com.pfplaybackend.api.administration.adapter.in.web.dto;

import java.time.LocalDateTime;

/**
 * A-4 POST /admin/members/{id}/withdraw response body.
 *
 * <p>{@code alreadyWithdrawn=true} signals an idempotent re-call (no state change,
 * no event publish, audit row count unchanged from prior call).
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b2-design.md §3.2
 */
public record AdminMemberWithdrawResponse(
        Long memberId,
        Long userAccountId,
        LocalDateTime withdrawnAt,
        boolean alreadyWithdrawn
) {}
