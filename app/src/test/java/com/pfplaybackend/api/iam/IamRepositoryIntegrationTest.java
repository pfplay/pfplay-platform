package com.pfplaybackend.api.iam;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test exercising the IAM repository slice after the V4 composition
 * refactor: {@link UserAccountData} now owns {@code email} and {@code providerType}
 * lookups, while {@link MemberData} / {@link GuestData} are queried by
 * {@code userAccountId}.
 *
 * <p>Uses the project-wide Testcontainers MySQL harness via
 * {@link AbstractIntegrationTest}.
 */
@Transactional
class IamRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserAccountRepository userAccountRepo;

    @Autowired
    private MemberRepository memberRepo;

    @Autowired
    private GuestRepository guestRepo;

    @Test
    @DisplayName("UserAccountRepository.findByEmailAndProviderType — 일치하는 계정을 반환한다")
    void findByEmailAndProviderType_returnsAccount() {
        // given
        userAccountRepo.save(UserAccountData.createForSocial(
                new UserId(101L), "alice@gmail.com", ProviderType.GOOGLE));
        flushAndClear();

        // when
        Optional<UserAccountData> found =
                userAccountRepo.findByEmailAndProviderType("alice@gmail.com", ProviderType.GOOGLE);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getUserId().getUid()).isEqualTo(101L);
        assertThat(found.get().getProviderType()).isEqualTo(ProviderType.GOOGLE);
    }

    @Test
    @DisplayName("UserAccountRepository.existsByEmail — 존재 여부를 정확히 반영한다")
    void existsByEmail_handlesPresenceAndAbsence() {
        // given
        userAccountRepo.save(UserAccountData.createForSocial(
                new UserId(102L), "bob@gmail.com", ProviderType.GOOGLE));
        flushAndClear();

        // when / then
        assertThat(userAccountRepo.existsByEmail("bob@gmail.com")).isTrue();
        assertThat(userAccountRepo.existsByEmail("nope@gmail.com")).isFalse();
    }

    @Test
    @DisplayName("UserAccountRepository.countByProviderType — 프로바이더별 계정 수를 집계한다")
    void countByProviderType_aggregates() {
        // given
        userAccountRepo.save(UserAccountData.createForSocial(
                new UserId(110L), "g1@gmail.com", ProviderType.GOOGLE));
        userAccountRepo.save(UserAccountData.createForSocial(
                new UserId(111L), "g2@gmail.com", ProviderType.GOOGLE));
        userAccountRepo.save(UserAccountData.createForLocal(
                new UserId(112L), "local@pfplay.local", "hashed"));
        flushAndClear();

        // when
        long googleCount = userAccountRepo.countByProviderType(ProviderType.GOOGLE);
        long localCount = userAccountRepo.countByProviderType(ProviderType.LOCAL);

        // then
        assertThat(googleCount).isGreaterThanOrEqualTo(2);
        assertThat(localCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("MemberRepository.findByUserAccountId — Member 를 조회한다")
    void member_findByUserAccountId_returnsMember() {
        // given
        userAccountRepo.save(UserAccountData.createForSocial(
                new UserId(200L), "carol@gmail.com", ProviderType.GOOGLE));
        memberRepo.save(MemberData.createForUserAccount(200L));
        flushAndClear();

        // when
        Optional<MemberData> found = memberRepo.findByUserAccountId(200L);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getUserAccountId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("MemberRepository.existsByUserAccountId — 존재/부재를 정확히 반영한다")
    void member_existsByUserAccountId_handlesPresenceAndAbsence() {
        // given
        userAccountRepo.save(UserAccountData.createForSocial(
                new UserId(201L), "dave@gmail.com", ProviderType.GOOGLE));
        memberRepo.save(MemberData.createForUserAccount(201L));
        flushAndClear();

        // when / then
        assertThat(memberRepo.existsByUserAccountId(201L)).isTrue();
        assertThat(memberRepo.existsByUserAccountId(999_999L)).isFalse();
    }

    @Test
    @DisplayName("GuestRepository.findByUserAccountId — Guest 를 조회한다")
    void guest_findByUserAccountId_returnsGuest() {
        // given
        userAccountRepo.save(UserAccountData.createForLocal(
                new UserId(300L), "guest-300@pfplay.local", "synthetic-hash"));
        guestRepo.save(GuestData.createForUserAccount(300L, "Mozilla/5.0"));
        flushAndClear();

        // when
        Optional<GuestData> found = guestRepo.findByUserAccountId(300L);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getUserAccountId()).isEqualTo(300L);
    }
}
