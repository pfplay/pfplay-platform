package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import java.time.LocalDateTime;

/**
 * B-4 디제잉 이력 아이템. avatarIconUri 는 미설정 DJ의 경우 "" (빈 문자열).
 */
public record AdminDjHistoryItemResponse(Long playbackId, String trackName,
                                         Long djUserAccountId, String djNickname,
                                         String avatarIconUri, String thumbnailImage,
                                         LocalDateTime playedAt) {}
