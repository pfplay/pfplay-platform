package com.pfplaybackend.api.partyview.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackReactionQueryService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.partyview.application.dto.result.PartyroomSetupResult;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomSetupQueryServiceTest {

    @Mock PartyroomQueryService partyroomQueryService;
    @Mock PlaybackQueryService playbackQueryService;
    @Mock PlaybackReactionQueryService playbackReactionQueryService;
    @Mock UserProfileQueryPort userProfileQueryPort;

    @InjectMocks PartyroomSetupQueryService partyroomSetupQueryService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    private ProfileSettingDto createProfileSetting(String nickname) {
        return new ProfileSettingDto(nickname, AvatarCompositionType.BODY_WITH_FACE,
                "body_uri", "face_uri", "icon_uri", 0, 0, 0.0, 0.0, 1.0);
    }

    /**
     * Build a {@link PartyroomData} mock with the given stageType.
     *
     * <p>Two gotchas worth flagging:
     * <ul>
     *   <li>Do NOT inline this inside {@code when(...).thenReturn(...)} — the inner stubbing
     *       will collide with the outer one and Mockito throws UnfinishedStubbingException.
     *       Always assign the return value to a local first, then pass it to thenReturn.</li>
     *   <li>{@code lenient()} is required because some tests stub a partyroom but the service
     *       throws before reaching the stageType lookup (e.g. no-active-room path). Strict
     *       mode would flag the unused stub.</li>
     * </ul>
     */
    private PartyroomData partyroomDataWith(StageType stageType) {
        PartyroomData partyroom = mock(PartyroomData.class);
        lenient().when(partyroom.getStageType()).thenReturn(stageType);
        return partyroom;
    }

    @Test
    @DisplayName("getSetupInfo — 활성 재생이 있을 때 DisplayDto에 재생 정보가 포함된다")
    void getSetupInfoActivePlayback() {
        // given
        UserId djUserId = new UserId(2L);
        PlaybackId playbackId = new PlaybackId(100L);
        CrewId djCrewId = new CrewId(2L);
        ActivePartyroomDto activeDto = new ActivePartyroomDto(partyroomId.getId(), false, 1L, true, playbackId, djCrewId);

        CrewData crew1 = CrewData.builder().id(1L).userId(userId).gradeType(GradeType.CLUBBER).build();
        CrewData djCrew = CrewData.builder().id(2L).userId(djUserId).gradeType(GradeType.CLUBBER).build();

        PlaybackData playback = PlaybackData.builder()
                .id(playbackId.getId()).partyroomId(partyroomId).userId(djUserId)
                .name("Song").linkId("link1").duration(Duration.fromString("3:00"))
                .thumbnailImage("thumb.jpg").endTime(999L).build();

        PlaybackAggregationData aggregation = PlaybackAggregationData.createFor(playbackId);

        PartyroomData partyroom = partyroomDataWith(StageType.MAIN);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(partyroomQueryService.getActiveCrews(partyroomId)).thenReturn(List.of(crew1, djCrew));
        when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenReturn(Map.of(
                        userId, createProfileSetting("User1"),
                        djUserId, createProfileSetting("DJ")
                ));
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeDto));
        when(playbackQueryService.getPlaybackById(playbackId)).thenReturn(playback);
        when(playbackQueryService.getPlaybackAggregationById(playbackId)).thenReturn(aggregation);
        when(playbackReactionQueryService.findPrevHistoryData(any(), any()))
                .thenReturn(Optional.empty());

        // when
        PartyroomSetupResult result = partyroomSetupQueryService.getSetupInfo(partyroomId);

        // then
        assertThat(result.stageType()).isEqualTo(StageType.MAIN);
        assertThat(result.display().playbackActivated()).isTrue();
        assertThat(result.display().playback()).isNotNull();
        assertThat(result.display().playback().getName()).isEqualTo("Song");
        assertThat(result.display().currentDj()).isNotNull();
        assertThat(result.crews()).hasSize(2);
    }

    @Test
    @DisplayName("getSetupInfo — 비활성 재생일 때 DisplayDto의 재생/리액션/DJ 정보가 null이다")
    void getSetupInfoInactivePlayback() {
        // given
        ActivePartyroomDto activeDto = new ActivePartyroomDto(partyroomId.getId(), false, 1L, false, null, null);
        CrewData crew = CrewData.builder().id(1L).userId(userId).gradeType(GradeType.CLUBBER).build();

        PartyroomData partyroom = partyroomDataWith(StageType.GENERAL);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(partyroomQueryService.getActiveCrews(partyroomId)).thenReturn(List.of(crew));
        when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenReturn(Map.of(userId, createProfileSetting("User1")));
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeDto));

        // when
        PartyroomSetupResult result = partyroomSetupQueryService.getSetupInfo(partyroomId);

        // then
        assertThat(result.stageType()).isEqualTo(StageType.GENERAL);
        assertThat(result.display().playbackActivated()).isFalse();
        assertThat(result.display().playback()).isNull();
        assertThat(result.display().reaction()).isNull();
        assertThat(result.display().currentDj()).isNull();
    }

    @Test
    @DisplayName("getSetupInfo — 활성 크루 목록이 프로필 설정 정보와 함께 반환된다")
    void getSetupInfoCrewsWithProfileSettings() {
        // given
        UserId user2 = new UserId(2L);
        ActivePartyroomDto activeDto = new ActivePartyroomDto(partyroomId.getId(), false, 1L, false, null, null);

        CrewData crew1 = CrewData.builder().id(1L).userId(userId).gradeType(GradeType.HOST).build();
        CrewData crew2 = CrewData.builder().id(2L).userId(user2).gradeType(GradeType.CLUBBER).build();

        PartyroomData partyroom = partyroomDataWith(StageType.MAIN);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(partyroomQueryService.getActiveCrews(partyroomId)).thenReturn(List.of(crew1, crew2));
        when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenReturn(Map.of(
                        userId, createProfileSetting("Host"),
                        user2, createProfileSetting("Clubber")
                ));
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeDto));

        // when
        PartyroomSetupResult result = partyroomSetupQueryService.getSetupInfo(partyroomId);

        // then
        assertThat(result.crews()).hasSize(2);
        assertThat(result.crews().get(0).nickname()).isEqualTo("Host");
        assertThat(result.crews().get(1).nickname()).isEqualTo("Clubber");
    }

    @Test
    @DisplayName("getSetupInfo — 활성 파티룸이 없으면 예외가 발생한다")
    void getSetupInfoNoActiveRoomThrows() {
        // given — partyroom 자체는 존재(stageType lookup 통과)하지만 호출자의 active crew 세션은 없음
        PartyroomData partyroom = partyroomDataWith(StageType.MAIN);
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(partyroomQueryService.getActiveCrews(partyroomId)).thenReturn(List.of());
        when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenReturn(Map.of());
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomSetupQueryService.getSetupInfo(partyroomId))
                .isInstanceOf(NotFoundException.class);
    }
}
