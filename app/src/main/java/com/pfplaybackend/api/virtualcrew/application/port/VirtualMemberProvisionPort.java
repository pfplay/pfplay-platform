package com.pfplaybackend.api.virtualcrew.application.port;

import com.pfplaybackend.api.user.domain.entity.data.MemberData;

/**
 * virtualcrew 가 봇 계정 프로비저닝을 위해 admin 바운디드 컨텍스트로 나가는 outbound port.
 *
 * <p>이 seam 의 목적은 {@code virtualcrew.application.*}(현재 풀 서비스 + Chunk 4 오케스트레이터)가
 * {@code ..admin..} 패키지를 직접 import 하지 못하게 막는 것이다. 구현체
 * ({@code virtualcrew.adapter.out.provision.VirtualMemberProvisionAdapter}) 만 admin 레이어를
 * 만질 수 있다 — Chunk 6 의 ArchUnit 규칙을 미리 만족시킨다.
 */
public interface VirtualMemberProvisionPort {

    /**
     * 실유저와 동일한 경로로 완전히 초기화된 LOCAL 가상 멤버를 생성하고 그 신원을 반환한다
     * (FM 승격 + 프로필·기본 아바타 + activity 행 + GRABLIST 포함).
     */
    MemberData createVirtualMember(String nickname);

    /**
     * 봇(가상 멤버)을 탈퇴(soft-delete)한다 — 실회원과 동일한 검증된 탈퇴 경로 재사용.
     * idempotent(이미 탈퇴면 no-op). 탈퇴 즉시 봇 풀·로스터·배치 대상 조회에서 제외된다.
     *
     * @param botUserId 봇의 user_account_id
     */
    void withdrawVirtualMember(Long botUserId);
}
