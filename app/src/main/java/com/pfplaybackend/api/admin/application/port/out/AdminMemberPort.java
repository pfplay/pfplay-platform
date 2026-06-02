package com.pfplaybackend.api.admin.application.port.out;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;

import java.util.Optional;

public interface AdminMemberPort {
    MemberData saveMember(MemberData member);
    Optional<MemberData> findMemberById(Long id);

    /**
     * 사용자 계정 id(user_account_id)로 멤버를 조회한다.
     *
     * <p>{@link #findMemberById}는 member 테이블 PK(member_id, IDENTITY)로 조회하지만,
     * 어드민 가상 멤버 운영은 user_account_id 로 멤버를 주소지정한다(봇 로스터·기존 어드민 가상멤버
     * 도구 모두 user_account_id 노출). member_id ≠ user_account_id 이므로 user_account_id 주소지정에는
     * 반드시 이 메서드를 써야 한다(코드베이스 다른 곳의 {@code findByUserAccountId} 관례와 동일).
     */
    Optional<MemberData> findMemberByUserAccountId(Long userAccountId);

    void deleteMemberById(Long id);
    Optional<MemberData> findMemberByEmail(String email);
    long countMembersByProviderType(ProviderType providerType);

    /**
     * Persist the default ActivityData rows for the given user. Member no
     * longer owns the activity collection (Task 5/11) — this delegates to
     * {@code UserActivityCommandService.createUserActivities} which writes
     * via {@code ActivityRepository}.
     */
    void createUserActivities(UserId userId);

    /**
     * Delete every activity row owned by the given user. Member no longer
     * owns the activity collection as a JPA association, so activity rows
     * do not cascade on member deletion — callers must clean up explicitly.
     * Used by {@code AdminUserService.deleteVirtualMember}.
     */
    void deleteUserActivities(UserId userId);
}
