package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.admin.application.service.AdminUserService;
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
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class VirtualUserPoolServiceIT extends AbstractIntegrationTest {

    // AdminProfileService.getDefaultAvatarBodyUri() 가 사용하는 고정 디폴트 바디 URI.
    // test 프로파일은 Flyway 비활성(create-drop + 시드 없음)이라 이 IT 에서 직접 시드한다.
    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    // assignRandomFromCatalog 가 고를 published 카탈로그(combinable + standalone + 기본 face).
    private static final String COMBINABLE_BODY_URI = "https://cdn.test/ava_body_basic_001.png";
    private static final String STANDALONE_BODY_URI = "https://cdn.test/ava_body_basic_002.png";
    private static final String STANDALONE_ICON_URI = "https://cdn.test/ava_icon_body_basic_002.png";
    private static final String FACE_URI = "https://cdn.test/ava_face_basic_001.png";
    private static final String FACE_ICON_URI = "https://cdn.test/ava_icon_face_basic_001.png";

    @Autowired private VirtualUserPoolService poolService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private AdminUserService adminUserService;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private AvatarFaceResourceRepository avatarFaceResourceRepository;
    @Autowired private PlaylistAggregatePort aggregatePort;

    @BeforeEach
    void seedCatalog() {
        // createVirtualMember 의 디폴트 바디 — prod 의 깨진 디폴트(ava_basic_001)를 그대로 재현한다:
        // combinable=true + icon_uri NULL. provision 이 디폴트 그대로 두면 SINGLE_BODY 아이콘(=body iconUri)
        // 이 NULL 이라 채팅 아이콘이 blank 가 된다(=P1 이 고치려는 그 갭). 이게 회귀 가드의 red 전제.
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    null,
                    ObtainmentType.BASIC, 0, true, true, 60, 41, null);
            avatarBodyResourceRepository.save(body);
        }
        // assignRandomFromCatalog 가 고를 published 후보들.
        // combinable 바디: icon_uri NULL → face 합성이 강제됨(BODY_WITH_FACE).
        seedPublishedBody("ava_body_basic_001_pub", COMBINABLE_BODY_URI, null, true, 60, 41);
        // standalone 바디: 자체 icon_uri 보유(SINGLE_BODY).
        seedPublishedBody("ava_body_basic_002_pub", STANDALONE_BODY_URI, STANDALONE_ICON_URI, false, 0, 0);
        // 기본 face: combinable 합성 시 face-pair 아이콘 제공.
        seedPublishedFace("ava_face_basic_001_pub", FACE_URI, FACE_ICON_URI);
    }

    private void seedPublishedBody(String name, String uri, String iconUri, boolean combinable, int px, int py) {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(uri) != null) {
            return;
        }
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                name, uri, iconUri, ObtainmentType.BASIC, 0, combinable, false, px, py, null);
        body.publish(null);   // PUBLISHED 여야 findPublishedBodies 에 노출
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
    @DisplayName("봇 N명 생성 — 실계정(is_dummy)·playlist 보유")
    void 봇_N명_생성_실계정과_playlist_보유() {
        List<UserId> bots = poolService.provision(3);

        flushAndClear();

        assertThat(bots).hasSize(3);
        for (UserId bot : bots) {
            UserAccountData account = userAccountRepository.findById(bot).orElseThrow();
            assertThat(account.isDummy()).isTrue();

            Long playlistId = poolService.playlistIdOf(bot);
            assertThat(playlistId).isNotNull();
        }
    }

    @Test
    @DisplayName("idle 봇 조회 — 활성 crew 가 있는 봇은 제외")
    void idle_봇_조회_방에_없는_봇만() {
        List<UserId> bots = poolService.provision(3);
        flushAndClear();

        // 봇 1명을 임의 파티룸의 활성 crew 로 만든다.
        UserId busyBot = bots.get(0);
        crewRepository.save(CrewData.create(
                new PartyroomId(999L), busyBot, GradeType.LISTENER, null));
        flushAndClear();

        List<UserId> idle = poolService.findIdleBots(10);

        assertThat(idle).contains(bots.get(1), bots.get(2));
        assertThat(idle).doesNotContain(busyBot);
    }

    @Test
    @DisplayName("findIdleBots 는 비활성 crew 만 가진 봇은 idle 로 본다")
    void 비활성_crew_봇은_idle() {
        List<UserId> bots = poolService.provision(1);
        flushAndClear();

        UserId bot = bots.get(0);
        CrewData crew = CrewData.create(new PartyroomId(998L), bot, GradeType.LISTENER, null);
        crew.deactivatePresence();
        crewRepository.save(crew);
        flushAndClear();

        List<UserId> idle = poolService.findIdleBots(10);
        assertThat(idle).contains(bot);
    }

    @Test
    @DisplayName("provision 후 모든 봇은 유효한 채팅 아이콘을 가진다 (null-icon 갭 제거, spec §0/§4-3)")
    void provision_후_모든_봇은_유효한_채팅_아이콘을_가진다() {
        // when: 생성 즉시 카탈로그 랜덤 변별 아바타가 부여돼야 한다.
        List<UserId> bots = poolService.provision(5);

        flushAndClear();

        // then: 각 봇의 user_profile.avatar_icon_uri 가 non-blank.
        // (현 디폴트 basic_001 combinable 의 NULL 아이콘 깨짐이 사라졌는지 = P1-D5 핵심 회귀 가드)
        for (UserId id : bots) {
            String iconUri = adminUserService.getVirtualMember(id)
                    .getProfileData().getAvatarSetting().getAvatarIconUriValue();
            assertThat(iconUri).as("bot %s avatar icon", id.getUid()).isNotBlank();

            // 회귀 가드(replace-vs-mutate): provision = createVirtualMember(1 row) + 자동 변별 update.
            // update 가 profile 을 교체하면 cascade 더블 인서트로 2 row 가 된다. create-drop 엔
            // uk_user_profile_nickname 제약이 없어 SQL 에러 없이 통과하므로 row 개수로 직접 막는다
            // (reference_ddl_auto_create_drop_hides_migration_drift).
            long profileRows = ((Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM user_profile WHERE user_id = :uid")
                    .setParameter("uid", id.getUid())
                    .getSingleResult()).longValue();
            assertThat(profileRows)
                    .as("bot %s user_profile row count (provision 후 정확히 1개)", id.getUid())
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("renameBot — 봇 닉네임이 실DB user_profile 에서 변경된다")
    void renameBot_updatesNicknameInDb() {
        UserId bot = poolService.provision(1).get(0);
        flushAndClear();

        poolService.renameBot(bot, "루나");
        flushAndClear();

        String nickname = adminUserService.getVirtualMember(bot)
                .getProfileData().getNicknameValue();
        assertThat(nickname).isEqualTo("루나");
    }

    @Test
    @DisplayName("renameBot — 다른 봇이 이미 쓰는 닉네임으로 변경 시 ConflictException(UNIQUE)")
    void renameBot_duplicateNickname_throws() {
        List<UserId> bots = poolService.provision(2);
        flushAndClear();
        UserId a = bots.get(0);
        UserId b = bots.get(1);
        poolService.renameBot(b, "디제이민수");
        flushAndClear();

        assertThatThrownBy(() -> poolService.renameBot(a, "디제이민수"))
                .isInstanceOf(com.pfplaybackend.api.common.exception.http.ConflictException.class);
    }

    @Test
    @DisplayName("봇 provision — PLAYLIST 타입이 정확히 1개(봇 전용)여야 한다 (#329 기본 플리 이중 생성 회귀 방지)")
    void provision_botHasExactlyOnePlaylistTypePlaylist() {
        List<UserId> bots = poolService.provision(1);
        flushAndClear();

        List<PlaylistData> playlists = aggregatePort.findPlaylistsByOwnerAndType(bots.get(0), PlaylistType.PLAYLIST);
        assertThat(playlists).hasSize(1); // 두 번째 PLAYLIST가 생기면 playlistIdOf 단건 조회 파손
    }
}
