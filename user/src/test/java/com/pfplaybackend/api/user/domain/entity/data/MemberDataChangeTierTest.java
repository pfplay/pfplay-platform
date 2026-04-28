package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain unit test for {@link MemberData#changeTier(AuthorityTier, Long)} (PR 12b2 G2).
 *
 * <p>Verifies pure mutation + {@link MemberTierChangedEvent} registration.
 * Service-layer guards (TIER_UNCHANGED, MEMBER_NOT_FOUND) are tested in
 * {@code AdminMemberTierCommandServiceTest}.
 */
class MemberDataChangeTierTest {

    @Test
    @DisplayName("changeTier: AM → GT 변경 + MemberTierChangedEvent 1건 등록")
    void changeTier_changesAuthorityTier_andRegistersEvent() {
        var member = MemberData.createForUserAccount(7777L);
        // initial tier is AM (per createForUserAccount factory)
        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.AM);

        member.changeTier(AuthorityTier.GT, 99L);

        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.GT);

        var events = member.pollDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(MemberTierChangedEvent.class);
        var e = (MemberTierChangedEvent) events.get(0);
        assertThat(e.getUserAccountId()).isEqualTo(7777L);
        assertThat(e.getOldTier()).isEqualTo(AuthorityTier.AM);
        assertThat(e.getNewTier()).isEqualTo(AuthorityTier.GT);
        assertThat(e.getByAdministratorId()).isEqualTo(99L);
        // memberId is null pre-persist — getAggregateId returns "null" string
        assertThat(e.getMemberId()).isNull();
    }

    @Test
    @DisplayName("changeTier: 두 번 호출 — 매번 event registered (idempotency는 service 책임)")
    void changeTier_twice_registersTwoEvents() {
        var member = MemberData.createForUserAccount(7777L);

        member.changeTier(AuthorityTier.GT, 99L);
        member.changeTier(AuthorityTier.FM, 99L);

        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.FM);
        assertThat(member.pollDomainEvents()).hasSize(2);
    }
}
