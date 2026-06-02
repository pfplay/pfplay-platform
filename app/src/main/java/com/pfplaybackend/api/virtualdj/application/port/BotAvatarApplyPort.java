package com.pfplaybackend.api.virtualdj.application.port;

import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;

/**
 * virtualdj 가 봇 멤버 아바타를 갱신하기 위해 admin BC 로 나가는 outbound port.
 * 구현체(provision 어댑터)만 admin 레이어를 만진다 — 합성/아이콘 결정은 admin 쪽 기존 로직 재사용.
 */
public interface BotAvatarApplyPort {
    /** 봇의 아바타를 (body, face) 로 갱신한다. face 빈값이면 SINGLE_BODY, 있으면 BODY_WITH_FACE 로 admin 이 합성/아이콘 결정. */
    void apply(UserId botUserId, AvatarBodyUri bodyUri, AvatarFaceUri faceUri);
}
