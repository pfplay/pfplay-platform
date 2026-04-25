package com.pfplaybackend.api.admin.application.port.out;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;

import java.util.Optional;

public interface AdminMemberPort {
    MemberData saveMember(MemberData member);
    Optional<MemberData> findMemberById(Long id);
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
