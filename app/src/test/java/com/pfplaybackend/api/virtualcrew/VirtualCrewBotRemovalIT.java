package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 봇 제거(탈퇴 soft-delete) 전체 체인 IT — {@link VirtualCrewAdminService#removeBots} →
 * {@link VirtualUserPoolService#withdrawBot} → provision 어댑터 → {@code AdminUserService.withdrawVirtualMember}
 * → {@code AdminMemberWithdrawCommandService.withdraw} → {@code UserAccountData.withdraw}.
 *
 * <p>검증: (1) idle 봇 탈퇴 시 {@code withdrawn_at} 세팅 + 풀/로스터/idle 조회에서 즉시 제외,
 * (2) 배치(활성 crew)된 봇이 섞이면 {@link VirtualCrewException#BOT_PLACED_CANNOT_REMOVE} 로 전체 거부.
 *
 * <p>{@code AdminContext} 는 실 SecurityContext 대신 mock — withdraw 는 auth principal 을 요구하므로
 * (AdminMemberWithdrawCommandServiceIT 와 동일 패턴). AFTER_COMMIT audit 리스너는 @Transactional
 * 롤백으로 안 뛰지만, 여기 관심사는 봇 풀 상태이므로 무관하다.
 */
@Transactional
class VirtualCrewBotRemovalIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";
    private static final String COMBINABLE_BODY_URI = "https://cdn.test/ava_body_basic_001.png";
    private static final String STANDALONE_BODY_URI = "https://cdn.test/ava_body_basic_002.png";
    private static final String STANDALONE_ICON_URI = "https://cdn.test/ava_icon_body_basic_002.png";
    private static final String FACE_URI = "https://cdn.test/ava_face_basic_001.png";
    private static final String FACE_ICON_URI = "https://cdn.test/ava_icon_face_basic_001.png";

    @Autowired private VirtualUserPoolService poolService;
    @Autowired private VirtualCrewAdminService adminService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private BotPoolQueryRepository botPoolQueryRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private AvatarFaceResourceRepository avatarFaceResourceRepository;

    @MockBean private AdminContext adminContext;

    @BeforeEach
    void setUp() {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            avatarBodyResourceRepository.save(AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI, null,
                    ObtainmentType.BASIC, 0, true, true, 60, 41, null));
        }
        seedPublishedBody("ava_body_basic_001_pub", COMBINABLE_BODY_URI, null, true, 60, 41);
        seedPublishedBody("ava_body_basic_002_pub", STANDALONE_BODY_URI, STANDALONE_ICON_URI, false, 0, 0);
        seedPublishedFace("ava_face_basic_001_pub", FACE_URI, FACE_ICON_URI);

        given(adminContext.currentAdministratorId()).willReturn(1L);
    }

    private void seedPublishedBody(String name, String uri, String iconUri, boolean combinable, int px, int py) {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(uri) != null) {
            return;
        }
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                name, uri, iconUri, ObtainmentType.BASIC, 0, combinable, false, px, py, null);
        body.publish(null);
        avatarBodyResourceRepository.save(body);
    }

    private void seedPublishedFace(String name, String uri, String iconUri) {
        if (avatarFaceResourceRepository.findOneAvatarResourceByResourceUri(uri) != null) {
            return;
        }
        AvatarFaceResourceData face = AvatarFaceResourceData.draft(name, uri, iconUri, null);
        face.publish(null);
        avatarFaceResourceRepository.save(face);
    }

    @Test
    @DisplayName("idle 봇 removeBots → withdrawn_at 세팅 + 풀/로스터/idle 조회에서 제외")
    void removeIdleBot_softDeletesAndDisappearsFromPool() {
        List<UserId> bots = poolService.provision(2);
        flushAndClear();
        UserId target = bots.get(0);
        UserId keep = bots.get(1);
        long beforeCount = botPoolQueryRepository.countBots();

        VirtualCrewAdminService.BotRemovalResult result = adminService.removeBots(List.of(target.getUid()));
        flushAndClear();

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.removedUserIds()).containsExactly(target.getUid());

        // withdrawn_at 세팅됨
        UserAccountData account = userAccountRepository.findById(target).orElseThrow();
        assertThat(account.isWithdrawn()).isTrue();

        // 풀 카운트 1 감소, 로스터/idle 후보에서 제외
        assertThat(botPoolQueryRepository.countBots()).isEqualTo(beforeCount - 1);
        assertThat(botPoolQueryRepository.findRoster())
                .extracting(row -> row.userId())
                .doesNotContain(target.getUid())
                .contains(keep.getUid());
        assertThat(poolService.findIdleBots(10)).doesNotContain(target).contains(keep);
    }

    @Test
    @DisplayName("배치(활성 crew)된 봇 포함 → BOT_PLACED_CANNOT_REMOVE, 전체 거부(탈퇴 0건)")
    void removePlacedBot_rejectsAll() {
        List<UserId> bots = poolService.provision(2);
        flushAndClear();
        UserId idle = bots.get(0);
        UserId placed = bots.get(1);
        // placed 봇을 임의 방의 활성 crew 로 만든다.
        crewRepository.save(CrewData.create(new PartyroomId(1234L), placed, GradeType.LISTENER, null));
        flushAndClear();

        assertThatThrownBy(() -> adminService.removeBots(List.of(idle.getUid(), placed.getUid())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(VirtualCrewException.BOT_PLACED_CANNOT_REMOVE.getMessage());

        // 전체 거부 — idle 봇도 탈퇴되지 않았다(all-or-nothing). removeBots 는 write 전에 던지므로
        // rollback-only 마킹 없이 아래 read 가 안전하다.
        assertThat(userAccountRepository.findById(idle).orElseThrow().isWithdrawn()).isFalse();
        assertThat(userAccountRepository.findById(placed).orElseThrow().isWithdrawn()).isFalse();
    }
}
