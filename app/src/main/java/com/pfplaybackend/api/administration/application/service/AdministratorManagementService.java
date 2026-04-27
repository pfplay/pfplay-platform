package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.CreateAdministratorRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorListResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorView;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.CreateAdministratorResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.application.util.TempPasswordGenerator;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.exception.AdministratorManagementException;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.service.MemberSignService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdministratorManagementService {

    private final AdministratorRepository administratorRepository;
    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final MemberSignService memberSignService;
    private final PasswordEncoder passwordEncoder;
    private final TempPasswordGenerator tempPasswordGenerator;

    @Transactional(readOnly = true)
    public AdministratorListResponse list(AdminRole roleFilter, boolean includeRevoked) {
        List<AdministratorData> all = administratorRepository.findAllByOrderByGrantedAtDesc();

        Stream<AdministratorData> stream = all.stream();
        if (roleFilter != null) stream = stream.filter(a -> a.getRole() == roleFilter);
        if (!includeRevoked) stream = stream.filter(a -> !a.isRevoked());
        List<AdministratorData> filtered = stream.toList();

        Set<Long> userAccountIds = filtered.stream()
                .map(AdministratorData::getUserAccountId)
                .collect(Collectors.toSet());

        Map<Long, UserAccountData> uaByUid = userAccountRepository
                .findAllById(userAccountIds.stream().map(UserId::new).toList())
                .stream()
                .collect(Collectors.toMap(ua -> ua.getUserId().getUid(), Function.identity()));

        Map<Long, MemberData> memberByUaId = memberRepository
                .findAllByUserAccountIdIn(userAccountIds)
                .stream()
                .collect(Collectors.toMap(MemberData::getUserAccountId, Function.identity()));

        List<AdministratorView> items = filtered.stream()
                .map(a -> toView(a, uaByUid.get(a.getUserAccountId()),
                                  memberByUaId.get(a.getUserAccountId())))
                .toList();

        return AdministratorListResponse.builder()
                .totalCount(items.size())
                .items(items)
                .build();
    }

    @Transactional
    public CreateAdministratorResponse create(CreateAdministratorRequest req, Long actorAdministratorId) {
        if (userAccountRepository.findByEmail(req.getEmail()).isPresent()) {
            throw ExceptionCreator.create(AdministratorManagementException.EMAIL_ALREADY_REGISTERED);
        }

        String tempPwd = tempPasswordGenerator.generate();
        String hash = passwordEncoder.encode(tempPwd);

        UserAccountData ua = userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(), req.getEmail(), hash));

        AdministratorData admin = administratorRepository.save(
                AdministratorData.createAdmin(ua.getUserId().getUid(), actorAdministratorId));

        Long memberId = null;
        boolean includeMember = req.getIncludeMemberProfile() == null
                || req.getIncludeMemberProfile();
        if (includeMember) {
            MemberData member = memberSignService.getOrCreateMemberFor(ua);
            memberId = member.getMemberId();
            // Bio.updateNickname (Task 5c) preserves the introduction; the @OneToOne
            // cascade flushes the profile change at transaction end.
            member.getProfileData().updateNickname(req.getNickname());
        }

        log.info("admin_management.create administrator_id={} user_id={} actor_administrator_id={}",
                admin.getAdministratorId(), ua.getUserId().getUid(), actorAdministratorId);

        return CreateAdministratorResponse.builder()
                .administratorId(admin.getAdministratorId())
                .userAccountId(ua.getUserId().getUid())
                .memberId(memberId)
                .tempPassword(tempPwd)
                .message("임시 비번은 첫 로그인 후 반드시 변경하세요.")
                .build();
    }

    @Transactional
    public void updateNickname(Long administratorId, String nickname) {
        AdministratorData admin = administratorRepository.findById(administratorId)
                .orElseThrow(() -> ExceptionCreator.create(
                        AdministratorManagementException.NOT_FOUND));
        MemberData member = memberRepository.findByUserAccountId(admin.getUserAccountId())
                .orElseThrow(() -> ExceptionCreator.create(
                        AdministratorManagementException.MEMBER_PROFILE_REQUIRED));
        member.getProfileData().updateNickname(nickname);
        log.info("admin_management.update_nickname administrator_id={}", administratorId);
    }

    @Transactional
    public Long attachMemberProfile(Long administratorId, String nickname) {
        AdministratorData admin = administratorRepository.findById(administratorId)
                .orElseThrow(() -> ExceptionCreator.create(
                        AdministratorManagementException.NOT_FOUND));
        UserAccountData ua = userAccountRepository.findById(new UserId(admin.getUserAccountId()))
                .orElseThrow(() -> new IllegalStateException(
                        "admin → ua invariant violated: user_id=" + admin.getUserAccountId()));
        MemberData member = memberSignService.getOrCreateMemberFor(ua);
        member.getProfileData().updateNickname(nickname);
        log.info("admin_management.attach_member_profile administrator_id={} member_id={}",
                administratorId, member.getMemberId());
        return member.getMemberId();
    }

    private AdministratorView toView(AdministratorData a, UserAccountData ua, MemberData member) {
        String nickname = null;
        Long memberId = null;
        if (member != null) {
            memberId = member.getMemberId();
            // Inside @Transactional readOnly — LAZY profileData init is fine here.
            if (member.getProfileData() != null) {
                nickname = member.getProfileData().getNicknameValue();
            }
        }
        return AdministratorView.builder()
                .administratorId(a.getAdministratorId())
                .role(a.getRole())
                .grantedAt(a.getGrantedAt())
                .grantedByAdministratorId(a.getGrantedByAdministratorId())
                .revokedAt(a.getRevokedAt())
                .userAccountId(a.getUserAccountId())
                .email(ua != null ? ua.getEmail() : null)
                .lastLoginAt(ua != null ? ua.getLastLoginAt() : null)
                .mustChangePassword(ua != null && ua.isMustChangePassword())
                .memberId(memberId)
                .nickname(nickname)
                .build();
    }
}
