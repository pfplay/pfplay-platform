package com.pfplaybackend.api.administration.adapter.in.web.dto;

/**
 * GET /admin/guests/{guestId} detail response — profile sub-payload.
 * MemberProfileSummary 와 shape 동일하나, guest-specific 필드 향후 추가 시 영향 격리를 위해 분리.
 * 두 필드 모두 nullable: guest 가 isProfileUpdated=false 면 미존재.
 */
public record GuestProfileSummary(
        String nickname,
        String introduction
) {}
