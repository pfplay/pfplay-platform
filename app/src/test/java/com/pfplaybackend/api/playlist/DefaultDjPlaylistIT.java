package com.pfplaybackend.api.playlist;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.playlist.application.service.PlaylistCommandService;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import com.pfplaybackend.api.user.application.service.initialize.TemporaryUserInitializeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 신규 가입자 기본 DJ 플레이리스트(#329) — 가입 세트·AM 상한·삭제 후 재생성 IT.
 *
 * <p>진입점은 게스트→회원 전환 경로({@link TemporaryUserInitializeService#addAssociateMember})를
 * 대표로 사용한다(플랜 확정 — MemberSignService 의 OAuth 경로는 배선 검증이 유닛으로 이미 충분).
 */
@Transactional
class DefaultDjPlaylistIT extends AbstractIntegrationTest {

    // AdminProfileService/UserAvatarQueryService 가 기본 아바타를 구성하는 데 필요한 최소 카탈로그.
    // Flyway V3가 아바타를 시드하지만 DatabaseCleaner 격리로 메서드 간 truncate될 수 있어 조건부 재시드한다.
    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";
    private static final String FACE_URI = "https://cdn.test/ava_face_basic_001.png";
    private static final String FACE_ICON_URI = "https://cdn.test/ava_icon_face_basic_001.png";

    @Autowired private TemporaryUserInitializeService temporaryUserInitializeService;
    @Autowired private PlaylistAggregatePort aggregatePort;
    @Autowired private PlaylistCommandService playlistCommandService;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private AvatarFaceResourceRepository avatarFaceResourceRepository;

    @BeforeEach
    void seedDefaultAvatarCatalog() {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    null,
                    ObtainmentType.BASIC, 0, true, true, 60, 41, null);
            avatarBodyResourceRepository.save(body);
        }
        if (avatarFaceResourceRepository.findOneAvatarResourceByResourceUri(FACE_URI) == null) {
            AvatarFaceResourceData face = AvatarFaceResourceData.draft(
                    "ava_face_basic_001_pub", FACE_URI, FACE_ICON_URI, null);
            face.publish(null);
            avatarFaceResourceRepository.save(face);
        }
    }

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    private UserId initializeNewMemberFixture() {
        UserId userId = new UserId();
        temporaryUserInitializeService.addAssociateMember(userId, "member-" + userId.getUid() + "@test.local");
        return userId;
    }

    private void setAuthContext(UserId userId, AuthorityTier tier) {
        ThreadLocalContext.setContext(new AuthContext(userId, tier));
    }

    @Test
    @DisplayName("가입 초기화 — GRABLIST 1 + '내 플레이리스트'(PLAYLIST) 1 이 생성된다")
    void signupInitialization_createsGrablistAndDefaultDjPlaylist() {
        UserId userId = initializeNewMemberFixture();
        flushAndClear();

        List<PlaylistData> grab = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.GRABLIST);
        List<PlaylistData> normal = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST);
        assertThat(grab).hasSize(1);
        assertThat(normal).hasSize(1);
        assertThat(normal.get(0).getName()).isEqualTo("내 플레이리스트");
    }

    @Test
    @DisplayName("AM 유저(기본 플리 보유)가 추가 생성 시도 → PLL-002 EXCEEDED_PLAYLIST_LIMIT")
    void amUserWithDefaultPlaylist_cannotCreateAnother() {
        UserId userId = initializeNewMemberFixture();
        flushAndClear();
        setAuthContext(userId, AuthorityTier.AM);

        assertThatThrownBy(() -> playlistCommandService.createPlaylist("두번째"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PLL-002");
    }

    @Test
    @DisplayName("기본 플리 삭제 후엔 다시 1개 생성 가능 — 락아웃 없음")
    void afterDeletingDefault_amUserCanCreateAgain() {
        UserId userId = initializeNewMemberFixture();
        flushAndClear();
        setAuthContext(userId, AuthorityTier.AM);

        Long defaultId = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST)
                .get(0).getId();
        playlistCommandService.deletePlaylist(List.of(defaultId));

        PlaylistData recreated = playlistCommandService.createPlaylist("직접 만든 플리");
        assertThat(recreated.getType()).isEqualTo(PlaylistType.PLAYLIST);
    }
}
