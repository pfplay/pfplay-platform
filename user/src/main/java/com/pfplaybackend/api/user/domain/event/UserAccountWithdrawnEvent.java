package com.pfplaybackend.api.user.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import lombok.Getter;

/**
 * UserAccount 탈퇴 이벤트.
 * - UserActivityLogListener listen → user_activity_log WITHDREW + ADMIN_ACTED_ON 2 row (PR 12b1)
 *
 * `byAdministratorId`는 PR 12b1 forward-evolution(PR 1 origin) — admin-trigger only 시맨틱.
 * spec roadmap §11.2: "탈퇴 시 last_login_at 보존, admin이 trigger". 사용자 self-withdrawal은 OOS.
 *
 * 발행 source: PR 12b2 `AdminMemberWithdrawCommandService` (현재 미구현). G2 시점 publisher
 * `UserAccountData.withdraw(Long byAdministratorId)`만 변경 — 호출자 0건이라 cascade 안전.
 */
@Getter
public class UserAccountWithdrawnEvent extends DomainEvent {
    private final Long userAccountId;
    private final String anonymizedEmail;
    private final Long byAdministratorId;

    public UserAccountWithdrawnEvent(Long userAccountId, String anonymizedEmail, Long byAdministratorId) {
        super();
        this.userAccountId = userAccountId;
        this.anonymizedEmail = anonymizedEmail;
        this.byAdministratorId = byAdministratorId;
    }

    @Override
    public String getAggregateId() {
        return userAccountId.toString();
    }
}
