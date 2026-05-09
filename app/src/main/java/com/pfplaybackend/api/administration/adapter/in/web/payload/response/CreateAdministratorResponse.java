package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import lombok.Builder;

@Builder
public record CreateAdministratorResponse(
        Long administratorId,
        Long userAccountId,
        Long memberId,           // null when includeMemberProfile=false
        String tempPassword,
        String message
) {}
