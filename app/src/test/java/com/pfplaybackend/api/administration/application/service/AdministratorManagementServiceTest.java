package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.CreateAdministratorRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorListResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorView;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.CreateAdministratorResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.ResetPasswordResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.application.util.TempPasswordGenerator;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdministratorManagementServiceTest {

    @Mock AdministratorRepository administratorRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock MemberRepository memberRepository;
    @Mock MemberSignService memberSignService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TempPasswordGenerator tempPasswordGenerator;
    @InjectMocks AdministratorManagementService service;

    // -------- list --------

    @Test
    void list_excludesRevokedByDefault_andSortsByGrantedAtDesc() {
        AdministratorData a1 = adminFixture(1L, 100L, AdminRole.SUPER_ADMIN, null);
        AdministratorData a2 = adminFixture(2L, 101L, AdminRole.ADMIN, null);
        AdministratorData a3 = adminFixture(3L, 102L, AdminRole.ADMIN, LocalDateTime.now()); // revoked

        given(administratorRepository.findAllByOrderByGrantedAtDesc())
                .willReturn(List.of(a1, a2, a3));
        given(userAccountRepository.findAllById(anyIterable()))
                .willReturn(List.of(uaFixture(100L, "s@x"), uaFixture(101L, "a@x"), uaFixture(102L, "r@x")));
        given(memberRepository.findAllByUserAccountIdIn(anyCollection()))
                .willReturn(List.of());

        AdministratorListResponse resp = service.list(null, false);

        assertThat(resp.totalCount()).isEqualTo(2);
        assertThat(resp.items()).extracting(AdministratorView::administratorId)
                .containsExactly(1L, 2L); // a3 excluded
    }

    @Test
    void list_includeRevokedTrue_returnsAll() {
        AdministratorData a1 = adminFixture(1L, 100L, AdminRole.SUPER_ADMIN, null);
        AdministratorData a2 = adminFixture(2L, 101L, AdminRole.ADMIN, null);
        AdministratorData a3 = adminFixture(3L, 102L, AdminRole.ADMIN, LocalDateTime.now());

        given(administratorRepository.findAllByOrderByGrantedAtDesc())
                .willReturn(List.of(a1, a2, a3));
        given(userAccountRepository.findAllById(anyIterable()))
                .willReturn(List.of(uaFixture(100L, "s@x"), uaFixture(101L, "a@x"), uaFixture(102L, "r@x")));
        given(memberRepository.findAllByUserAccountIdIn(anyCollection()))
                .willReturn(List.of());

        AdministratorListResponse resp = service.list(null, true);

        assertThat(resp.totalCount()).isEqualTo(3);
        assertThat(resp.items()).extracting(AdministratorView::administratorId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void list_filtersByRoleWhenProvided() {
        AdministratorData a1 = adminFixture(1L, 100L, AdminRole.SUPER_ADMIN, null);
        AdministratorData a2 = adminFixture(2L, 101L, AdminRole.ADMIN, null);
        AdministratorData a3 = adminFixture(3L, 102L, AdminRole.ADMIN, null);

        given(administratorRepository.findAllByOrderByGrantedAtDesc())
                .willReturn(List.of(a1, a2, a3));
        given(userAccountRepository.findAllById(anyIterable()))
                .willReturn(List.of(uaFixture(101L, "a@x"), uaFixture(102L, "b@x")));
        given(memberRepository.findAllByUserAccountIdIn(anyCollection()))
                .willReturn(List.of());

        AdministratorListResponse resp = service.list(AdminRole.ADMIN, false);

        assertThat(resp.totalCount()).isEqualTo(2);
        assertThat(resp.items()).extracting(AdministratorView::administratorId)
                .containsExactly(2L, 3L);
    }

    @Test
    void list_populatesEmail_lastLoginAt_mustChangePassword_fromUserAccount() {
        AdministratorData a1 = adminFixture(1L, 100L, AdminRole.ADMIN, null);

        UserAccountData ua = UserAccountData.createForLocalWithMandatoryChange(
                new UserId(100L), "admin@x.com", "hash");

        given(administratorRepository.findAllByOrderByGrantedAtDesc())
                .willReturn(List.of(a1));
        given(userAccountRepository.findAllById(anyIterable()))
                .willReturn(List.of(ua));
        given(memberRepository.findAllByUserAccountIdIn(anyCollection()))
                .willReturn(List.of());

        AdministratorListResponse resp = service.list(null, false);

        AdministratorView view = resp.items().get(0);
        assertThat(view.email()).isEqualTo("admin@x.com");
        assertThat(view.mustChangePassword()).isTrue();
    }

    @Test
    void list_populatesMemberId_andNickname_whenMemberExists() {
        AdministratorData a1 = adminFixture(1L, 100L, AdminRole.ADMIN, null);
        UserAccountData ua = uaFixture(100L, "a@x");

        MemberData member = mock(MemberData.class);
        ProfileData profile = mock(ProfileData.class);
        given(member.getMemberId()).willReturn(50L);
        given(member.getUserAccountId()).willReturn(100L);
        given(member.getProfileData()).willReturn(profile);
        given(profile.getNicknameValue()).willReturn("myNick");

        given(administratorRepository.findAllByOrderByGrantedAtDesc())
                .willReturn(List.of(a1));
        given(userAccountRepository.findAllById(anyIterable()))
                .willReturn(List.of(ua));
        given(memberRepository.findAllByUserAccountIdIn(anyCollection()))
                .willReturn(List.of(member));

        AdministratorListResponse resp = service.list(null, false);

        AdministratorView view = resp.items().get(0);
        assertThat(view.memberId()).isEqualTo(50L);
        assertThat(view.nickname()).isEqualTo("myNick");
    }

    @Test
    void list_leavesMemberAndNicknameNullWhenMemberMissing() {
        AdministratorData a1 = adminFixture(1L, 100L, AdminRole.ADMIN, null);
        UserAccountData ua = uaFixture(100L, "a@x");

        given(administratorRepository.findAllByOrderByGrantedAtDesc())
                .willReturn(List.of(a1));
        given(userAccountRepository.findAllById(anyIterable()))
                .willReturn(List.of(ua));
        given(memberRepository.findAllByUserAccountIdIn(anyCollection()))
                .willReturn(List.of());

        AdministratorListResponse resp = service.list(null, false);

        AdministratorView view = resp.items().get(0);
        assertThat(view.memberId()).isNull();
        assertThat(view.nickname()).isNull();
    }

    // -------- create --------

    @Test
    void create_emailAlreadyRegistered_throws409() {
        given(userAccountRepository.findByEmail("dup@x"))
                .willReturn(Optional.of(uaFixture(99L, "dup@x")));

        assertThatThrownBy(() -> service.create(
                new CreateAdministratorRequest("dup@x", "n", true), 1L))
                .satisfies(ex -> {
                    assertThat(ex.getClass().getSimpleName()).contains("Conflict");
                });
    }

    @Test
    void create_includeMemberProfileNull_treatsAsTrue_andCreatesAllAggregates() {
        givenNoExistingEmail("new@x");
        given(tempPasswordGenerator.generate()).willReturn("Xk9@aB2zCdEf");
        given(passwordEncoder.encode("Xk9@aB2zCdEf")).willReturn("BCRYPT");
        given(userAccountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        AdministratorData savedAdmin = mock(AdministratorData.class);
        given(savedAdmin.getAdministratorId()).willReturn(7L);
        given(administratorRepository.save(any())).willReturn(savedAdmin);
        MemberData savedMember = mockMemberWithProfile(50L);
        given(memberSignService.getOrCreateMemberFor(any())).willReturn(savedMember);

        CreateAdministratorResponse resp = service.create(
                new CreateAdministratorRequest("new@x", "newbie", null),
                /*actorAdministratorId*/ 1L);

        assertThat(resp.tempPassword()).isEqualTo("Xk9@aB2zCdEf");
        assertThat(resp.administratorId()).isEqualTo(7L);
        assertThat(resp.memberId()).isEqualTo(50L);

        ArgumentCaptor<UserAccountData> uaCaptor = ArgumentCaptor.forClass(UserAccountData.class);
        verify(userAccountRepository).save(uaCaptor.capture());
        UserAccountData savedUa = uaCaptor.getValue();
        assertThat(savedUa.getProviderType()).isEqualTo(ProviderType.LOCAL);
        assertThat(savedUa.isMustChangePassword()).isTrue();
        assertThat(savedUa.getPasswordHash()).isEqualTo("BCRYPT");

        verify(memberSignService).getOrCreateMemberFor(savedUa);
        // profile nickname was set
        verify(savedMember.getProfileData()).updateNickname("newbie");
    }

    @Test
    void create_includeMemberProfileFalse_skipsMemberPath() {
        givenNoExistingEmail("new@x");
        given(tempPasswordGenerator.generate()).willReturn("Pwd#012345Xy");
        given(passwordEncoder.encode("Pwd#012345Xy")).willReturn("BCRYPT");
        given(userAccountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        AdministratorData savedAdmin = mock(AdministratorData.class);
        given(savedAdmin.getAdministratorId()).willReturn(7L);
        given(administratorRepository.save(any())).willReturn(savedAdmin);

        CreateAdministratorResponse resp = service.create(
                new CreateAdministratorRequest("new@x", "n", false),
                1L);

        assertThat(resp.memberId()).isNull();
        verify(memberSignService, never()).getOrCreateMemberFor(any());
    }

    @Test
    void create_passesActorAdministratorIdAsGrantedBy() {
        givenNoExistingEmail("new@x");
        given(tempPasswordGenerator.generate()).willReturn("Pwd#012345Xy");
        given(passwordEncoder.encode(anyString())).willReturn("BCRYPT");
        given(userAccountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AdministratorData> adminCaptor = ArgumentCaptor.forClass(AdministratorData.class);
        AdministratorData savedAdmin = mock(AdministratorData.class);
        given(savedAdmin.getAdministratorId()).willReturn(99L);
        given(administratorRepository.save(adminCaptor.capture())).willReturn(savedAdmin);

        service.create(new CreateAdministratorRequest("new@x", "n", false), /*actor*/ 42L);

        AdministratorData captured = adminCaptor.getValue();
        assertThat(captured.getRole()).isEqualTo(AdminRole.ADMIN);
        assertThat(captured.getGrantedByAdministratorId()).isEqualTo(42L);
    }

    // -------- updateNickname --------

    @Test
    void updateNickname_whenMemberExists_writesProfileNickname() {
        AdministratorData admin = mock(AdministratorData.class);
        given(admin.getUserAccountId()).willReturn(100L);
        given(administratorRepository.findById(7L)).willReturn(Optional.of(admin));
        MemberData member = mockMemberWithProfile(50L);
        given(memberRepository.findByUserAccountId(100L)).willReturn(Optional.of(member));

        service.updateNickname(7L, "renamed");

        verify(member.getProfileData()).updateNickname("renamed");
    }

    @Test
    void updateNickname_whenAdminNotFound_throwsNotFound() {
        given(administratorRepository.findById(7L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateNickname(7L, "renamed"))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("NotFound"));
    }

    @Test
    void updateNickname_whenMemberMissing_throwsMemberProfileRequired() {
        AdministratorData admin = mock(AdministratorData.class);
        given(admin.getUserAccountId()).willReturn(100L);
        given(administratorRepository.findById(7L)).willReturn(Optional.of(admin));
        given(memberRepository.findByUserAccountId(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateNickname(7L, "renamed"))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("Conflict"));
    }

    // -------- attachMemberProfile --------

    @Test
    void attachMemberProfile_returnsMemberId_andSetsNickname() {
        AdministratorData admin = mock(AdministratorData.class);
        given(admin.getUserAccountId()).willReturn(100L);
        given(administratorRepository.findById(7L)).willReturn(Optional.of(admin));
        UserAccountData ua = uaFixture(100L, "admin@x");
        given(userAccountRepository.findById(new UserId(100L))).willReturn(Optional.of(ua));
        MemberData member = mockMemberWithProfile(50L);
        given(memberSignService.getOrCreateMemberFor(ua)).willReturn(member);

        Long memberId = service.attachMemberProfile(7L, "newbie");

        assertThat(memberId).isEqualTo(50L);
        verify(member.getProfileData()).updateNickname("newbie");
    }

    @Test
    void attachMemberProfile_whenAdminNotFound_throwsNotFound() {
        given(administratorRepository.findById(7L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.attachMemberProfile(7L, "n"))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("NotFound"));
    }

    // -------- revoke --------

    @Test
    void revoke_normalAdmin_callsRevoke() {
        AdministratorData target = mock(AdministratorData.class);
        given(target.getRole()).willReturn(AdminRole.ADMIN);
        given(administratorRepository.findById(7L)).willReturn(Optional.of(target));

        service.revoke(7L, /*actor=*/ 1L);

        verify(target).revoke();
    }

    @Test
    void revoke_selfRevoke_throwsCannotRevokeSelf() {
        assertThatThrownBy(() -> service.revoke(1L, 1L))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("Conflict"));
        verify(administratorRepository, never()).findById(any());
    }

    @Test
    void revoke_targetNotFound_throwsNotFound() {
        given(administratorRepository.findById(7L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(7L, /*actor=*/ 99L))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("NotFound"));
    }

    @Test
    void revoke_lastActiveSuperAdmin_throwsConflict() {
        AdministratorData target = mock(AdministratorData.class);
        given(target.getRole()).willReturn(AdminRole.SUPER_ADMIN);
        given(administratorRepository.findById(1L)).willReturn(Optional.of(target));
        given(administratorRepository.countByRoleAndRevokedAtIsNull(AdminRole.SUPER_ADMIN))
                .willReturn(1L);

        assertThatThrownBy(() -> service.revoke(1L, /*actor=*/ 99L))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("Conflict"));
        verify(target, never()).revoke();
    }

    // -------- resetPassword --------

    @Test
    void resetPassword_setsTempPasswordHash_setsMustChangeFlag_returnsTempPlaintext() {
        AdministratorData target = mock(AdministratorData.class);
        given(target.getUserAccountId()).willReturn(100L);
        given(administratorRepository.findById(7L)).willReturn(Optional.of(target));
        UserAccountData ua = mock(UserAccountData.class);
        given(userAccountRepository.findById(new UserId(100L))).willReturn(Optional.of(ua));
        given(tempPasswordGenerator.generate()).willReturn("Yp4@xQ7zVwLm");
        given(passwordEncoder.encode("Yp4@xQ7zVwLm")).willReturn("BCRYPT");

        ResetPasswordResponse resp = service.resetPassword(7L, /*actor=*/ 1L);

        assertThat(resp.tempPassword()).isEqualTo("Yp4@xQ7zVwLm");
        verify(ua).requirePasswordChange("BCRYPT");
    }

    @Test
    void resetPassword_targetNotFound_throwsNotFound() {
        given(administratorRepository.findById(7L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.resetPassword(7L, 1L))
                .satisfies(ex -> assertThat(ex.getClass().getSimpleName()).contains("NotFound"));
    }

    // -------- helpers --------

    private AdministratorData adminFixture(Long id, Long uaId, AdminRole role, LocalDateTime revokedAt) {
        AdministratorData mock = mock(AdministratorData.class);
        // lenient: not every test exercises every stubbed method on every fixture
        // (e.g. revoked admins are filtered before toView; role stubs on non-matching rows go unused)
        lenient().when(mock.getAdministratorId()).thenReturn(id);
        lenient().when(mock.getUserAccountId()).thenReturn(uaId);
        lenient().when(mock.getRole()).thenReturn(role);
        lenient().when(mock.getGrantedAt()).thenReturn(LocalDateTime.now());
        lenient().when(mock.getGrantedByAdministratorId()).thenReturn(null);
        lenient().when(mock.getRevokedAt()).thenReturn(revokedAt);
        lenient().when(mock.isRevoked()).thenReturn(revokedAt != null);
        return mock;
    }

    private UserAccountData uaFixture(Long uid, String email) {
        return UserAccountData.createForLocalWithMandatoryChange(
                new UserId(uid), email, "h");
    }

    private void givenNoExistingEmail(String email) {
        given(userAccountRepository.findByEmail(email)).willReturn(Optional.empty());
    }

    private MemberData mockMemberWithProfile(Long memberId) {
        MemberData member = mock(MemberData.class);
        ProfileData profile = mock(ProfileData.class);
        lenient().when(member.getMemberId()).thenReturn(memberId);
        given(member.getProfileData()).willReturn(profile);
        return member;
    }
}
