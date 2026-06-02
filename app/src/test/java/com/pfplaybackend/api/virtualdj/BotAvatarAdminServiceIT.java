package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAdminService;
import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAssigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BotAvatarAdminService 통합 테스트 — 실 트랜잭션 매니저 + Testcontainer DB.
 *
 * <p><b>비-@Transactional 이다(의도적).</b> 클래스에 @Transactional 을 걸면 distribute 의 @Transactional
 * 이 테스트 트랜잭션에 join 만 하고 실제 커밋 경계가 테스트 종료 시점으로 미뤄져, rollback-only 마킹이
 * 던지는 {@link org.springframework.transaction.UnexpectedRollbackException} 이 distribute 호출 시점에
 * 드러나지 않는다(=버그를 못 잡음). 비-Tx 로 두어 distribute 자신의 @Transactional 을 실제 커밋 경계로
 * 만들고, 재조회로 "커밋됐는가"를 확인한다.
 *
 * <p>C1(핵심): 사전필터 도입 전이면 distribute 안에서 비-봇 apply 가 공유 tx 를 rollback-only 로 마킹 →
 * distribute 커밋 시 UnexpectedRollbackException + 성공분까지 롤백. 도입 후엔 유효 봇만 넘어가 정상 커밋.
 *
 * <p>I2(combinable 아이콘): combinable 바디는 icon_uri 가 NULL 이라, assignOne 이 기본 face 를 합성
 * (BODY_WITH_FACE)해 face-pair 아이콘으로 채워야 최종 avatarIconUri 가 non-blank 가 된다.
 *
 * <p>test 프로파일은 create-drop + Flyway 비활성(시드 없음)이라 카탈로그를 직접 시드한다(다른 IT 동일).
 */
class BotAvatarAdminServiceIT extends AbstractIntegrationTest {

    private static final String COMBINABLE_BODY_URI = "https://cdn.test/ava_body_basic_001.png";
    private static final String STANDALONE_BODY_URI = "https://cdn.test/ava_body_basic_002.png";
    private static final String FACE_URI = "https://cdn.test/ava_face_basic_001.png";
    private static final String FACE_ICON_URI = "https://cdn.test/ava_icon_face_basic_001.png";
    private static final String STANDALONE_ICON_URI = "https://cdn.test/ava_icon_body_basic_002.png";

    private static final long NON_BOT_MISSING_UID = 7_999_001L;

    @Autowired private BotAvatarAdminService botAvatarAdminService;
    @Autowired private AdminUserService adminUserService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private AvatarFaceResourceRepository avatarFaceResourceRepository;

    // 비-Tx IT 라 생성한 봇이 커밋돼 같은 컨테이너를 공유하는 다른 IT(BotPoolQueryRepositoryImplIT 등)를
    // 오염시킨다. @AfterEach 에서 withdraw 처리해 봇 조회(is_dummy + withdrawn_at IS NULL)에서 제외한다.
    private final List<UserId> createdBots = new ArrayList<>();

    @BeforeEach
    void seedCatalog() {
        // combinable 바디: icon_uri NULL → face 합성이 강제됨.
        seedBody("ava_body_basic_001", COMBINABLE_BODY_URI, null, true, true, 60, 41);
        // standalone 바디: 자체 icon_uri 보유.
        seedBody("ava_body_basic_002", STANDALONE_BODY_URI, STANDALONE_ICON_URI, false, false, 0, 0);
        // 기본 face: face-pair 아이콘 보유.
        seedFace("ava_face_basic_001", FACE_URI, FACE_ICON_URI);
    }

    private void seedBody(String name, String uri, String iconUri, boolean combinable,
                          boolean defaultSetting, int px, int py) {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(uri) != null) {
            return;
        }
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                name, uri, iconUri, ObtainmentType.BASIC, 0, combinable, defaultSetting, px, py, null);
        body.publish(null);   // PUBLISHED 여야 findPublishedBodies/Faces 에 노출
        avatarBodyResourceRepository.save(body);
    }

    private void seedFace(String name, String uri, String iconUri) {
        if (avatarFaceResourceRepository.findOneAvatarResourceByResourceUri(uri) != null) {
            return;
        }
        AvatarFaceResourceData face = AvatarFaceResourceData.draft(name, uri, iconUri, null);
        face.publish(null);
        avatarFaceResourceRepository.save(face);
    }

    /**
     * 실제 봇(LOCAL 가상 멤버 + is_dummy)을 1명 만든다. createVirtualMember 와 markAsDummy 는
     * 각자 자기 트랜잭션에서 커밋된다(테스트가 비-Tx 이므로).
     */
    private UserId seedBot(String initialBodyUri) {
        MemberData member = adminUserService.createVirtualMember(
                null, new AvatarBodyUri(initialBodyUri), new AvatarFaceUri(""));
        UserId botId = new UserId(member.getUserAccountId());
        UserAccountData account = userAccountRepository.findById(botId).orElseThrow();
        account.markAsDummy();
        userAccountRepository.saveAndFlush(account);
        createdBots.add(botId);
        return botId;
    }

    @AfterEach
    void withdrawCreatedBots() {
        for (UserId botId : createdBots) {
            userAccountRepository.findById(botId).ifPresent(account -> {
                account.withdraw(null);
                userAccountRepository.saveAndFlush(account);
            });
        }
        createdBots.clear();
    }

    private String currentBodyUri(UserId botId) {
        return adminUserService.getVirtualMember(botId)
                .getProfileData().getAvatarSetting().getAvatarBodyUriValue();
    }

    private String currentIconUri(UserId botId) {
        return adminUserService.getVirtualMember(botId)
                .getProfileData().getAvatarSetting().getAvatarIconUriValue();
    }

    /**
     * 봇 user_id 에 매달린 user_profile row 개수(네이티브 카운트).
     *
     * <p>이 가드가 replace-vs-mutate 더블 인서트를 직접 잡는다: test 프로파일은 create-drop 이라
     * uk_user_profile_nickname 제약이 없어(=Flyway V15 부재) 더블 인서트가 SQL 에러 없이 통과해버린다.
     * 따라서 제약에 의존하지 않고 row 개수를 세어 회귀를 막는다
     * (reference_ddl_auto_create_drop_hides_migration_drift).
     */
    private long profileRowCount(UserId botId) {
        Number count = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM user_profile WHERE user_id = :uid")
                .setParameter("uid", botId.getUid())
                .getSingleResult();
        return count.longValue();
    }

    @Test
    @DisplayName("C1: distribute([validBot, nonBot]) — 유효 봇 아바타가 실제로 커밋, 비-봇 격리, 결과 1건, 롤백 없음")
    void distribute_validBotCommitted_nonBotSkipped_noRollback() {
        UserId validBot = seedBot(STANDALONE_BODY_URI);
        assertThat(currentBodyUri(validBot)).isEqualTo(STANDALONE_BODY_URI);

        // 비-봇/미존재 id 를 섞는다. 사전필터 전이면 apply 예외 → 공유 tx rollback-only →
        // distribute 커밋 시 UnexpectedRollbackException + 유효 봇 변경까지 롤백.
        List<BotAvatarAssigner.Assigned> assigned = botAvatarAdminService.distribute(
                List.of(validBot.getUid(), NON_BOT_MISSING_UID),
                List.of(COMBINABLE_BODY_URI));

        // 비-봇은 격리 → 결과는 유효 봇 1건.
        assertThat(assigned).hasSize(1);
        assertThat(assigned.get(0).userId()).isEqualTo(validBot.getUid());
        assertThat(assigned.get(0).avatarBodyUri()).isEqualTo(COMBINABLE_BODY_URI);

        // 핵심: 유효 봇의 아바타 변경이 실제로 커밋됐다(롤백되지 않음). 재조회로 증명.
        assertThat(currentBodyUri(validBot)).isEqualTo(COMBINABLE_BODY_URI);
    }

    @Test
    @DisplayName("I2: setIndividual(combinable body) — face 합성으로 최종 채팅 아이콘(avatarIconUri)이 non-blank")
    void setIndividual_combinableBody_yieldsNonBlankIcon() {
        UserId bot = seedBot(STANDALONE_BODY_URI);

        botAvatarAdminService.setIndividual(bot, COMBINABLE_BODY_URI);

        assertThat(currentBodyUri(bot)).isEqualTo(COMBINABLE_BODY_URI);
        // combinable → 기본 face 합성 → face-pair 아이콘이 채워져 non-blank.
        assertThat(currentIconUri(bot)).isNotBlank();
        // 회귀 가드: update 는 기존 row 를 mutate 해야 한다. profile 교체(=cascade 더블 인서트)면 2가 된다.
        assertThat(profileRowCount(bot))
                .as("setIndividual 후 봇 %s 의 user_profile row 는 정확히 1개여야(교체 아님 mutate)", bot.getUid())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("회귀 가드: provision→update 반복해도 user_profile row 는 봇당 정확히 1개 (replace-vs-mutate 더블 인서트 차단)")
    void repeatedUpdate_keepsExactlyOneProfileRow() {
        UserId bot = seedBot(STANDALONE_BODY_URI);
        assertThat(profileRowCount(bot)).isEqualTo(1L);   // 생성 직후 1개

        // 여러 번 갱신해도(교체였다면 매번 +1) row 는 1개로 유지돼야 한다.
        botAvatarAdminService.setIndividual(bot, COMBINABLE_BODY_URI);
        botAvatarAdminService.setIndividual(bot, STANDALONE_BODY_URI);
        botAvatarAdminService.setIndividual(bot, COMBINABLE_BODY_URI);

        assertThat(profileRowCount(bot))
                .as("update 3회 후 봇 %s 의 user_profile row 는 여전히 1개여야", bot.getUid())
                .isEqualTo(1L);
        assertThat(currentBodyUri(bot)).isEqualTo(COMBINABLE_BODY_URI);
        assertThat(currentIconUri(bot)).isNotBlank();
    }
}
