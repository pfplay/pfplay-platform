package com.pfplaybackend.api.avatar.domain.event;

/**
 * 아바타 리소스 종류 — 이벤트 페이로드와 audit target_type 양쪽에서 사용.
 *
 * <p>이름은 {@code AdminActionTargetType}의 값과 동일해야 하므로 변경 시 주의.
 * (Administration 리스너가 {@code AdminActionTargetType.valueOf(name())}로 매핑.)
 */
public enum AvatarResourceType {
    AVATAR_BODY,
    AVATAR_FACE
}
