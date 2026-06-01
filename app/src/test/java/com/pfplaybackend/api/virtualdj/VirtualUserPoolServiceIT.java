package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class VirtualUserPoolServiceIT extends AbstractIntegrationTest {

    // AdminProfileService.getDefaultAvatarBodyUri() 가 사용하는 고정 디폴트 바디 URI.
    // test 프로파일은 Flyway 비활성(create-drop + 시드 없음)이라 이 IT 에서 직접 시드한다.
    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private VirtualUserPoolService poolService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;

    @BeforeEach
    void seedDefaultAvatarBody() {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) != null) {
            return;
        }
        // SINGLE_BODY 경로(face 비움)만 타도록 combinable=false + icon_uri 보유.
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "ava_body_basic_001", DEFAULT_BODY_URI,
                "https://example.test/icon_basic_001.png",
                ObtainmentType.BASIC, 0, false, true, 60, 41, null);
        avatarBodyResourceRepository.save(body);
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
}
