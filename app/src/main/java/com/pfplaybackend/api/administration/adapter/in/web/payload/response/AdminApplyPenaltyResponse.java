package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

/**
 * penaltyId는 PERMANENT_EXPULSION일 때만 non-null. ONE_TIME_EXPULSION은 history row 없음 → null.
 * 클라이언트는 PERMANENT 사례에서만 release 호출 가능.
 */
public record AdminApplyPenaltyResponse(Long penaltyId) {}
