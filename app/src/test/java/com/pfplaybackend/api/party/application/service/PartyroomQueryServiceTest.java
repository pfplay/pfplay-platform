package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.application.dto.crew.CrewDto;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomWithCrewDto;
import com.pfplaybackend.api.party.application.dto.result.CrewProfileSummaryResult;
import com.pfplaybackend.api.party.application.dto.result.DjQueueInfoResult;
import com.pfplaybackend.api.party.application.dto.result.PartyroomSummaryResult;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.QueueStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.*;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSummaryDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomQueryServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PartyroomQueryPort queryPort;
    @Mock UserProfileQueryPort userProfileQueryPort;
    @Mock PlaybackQueryService playbackQueryService;

    @InjectMocks PartyroomQueryService partyroomQueryService;

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

    @Test
    @DisplayName("getAllPartyrooms — 파티룸 목록에서 MODERATOR 이상 크루만 3명까지 필터링된다")
    void getAllPartyroomsFiltersCrewsByGradeAndLimit() {
        // given
        CrewDto host = new CrewDto(1L, new UserId(100L), GradeType.HOST);
        CrewDto communityManager = new CrewDto(2L, new UserId(101L), GradeType.COMMUNITY_MANAGER);
        CrewDto moderator = new CrewDto(3L, new UserId(102L), GradeType.MODERATOR);
        CrewDto moderator2 = new CrewDto(4L, new UserId(103L), GradeType.MODERATOR);
        CrewDto clubber = new CrewDto(5L, new UserId(104L), GradeType.CLUBBER);
        CrewDto listener = new CrewDto(6L, new UserId(105L), GradeType.LISTENER);

        PartyroomWithCrewDto dto = new PartyroomWithCrewDto(
                1L, StageType.GENERAL, new UserId(100L), "Test Room", "Hello",
                false, false, 6L, null,
                List.of(host, communityManager, moderator, moderator2, clubber, listener)
        );
        when(queryPort.getCrewDataByPartyroomId()).thenReturn(List.of(dto));

        // when
        List<PartyroomWithCrewDto> result = partyroomQueryService.getAllPartyrooms();

        // then
        assertThat(result).hasSize(1);
        List<CrewDto> filteredCrews = result.get(0).crews();
        assertThat(filteredCrews).hasSize(3)
                .allMatch(c -> c.gradeType().isEqualOrHigherThan(GradeType.MODERATOR));
    }

    @Test
    @DisplayName("getPartyroomById — 파티룸이 존재하면 반환한다")
    void getPartyroomByIdFoundReturns() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId())
                .partyroomId(partyroomId)
                .title("Test Room")
                .introduction("Hello")
                .linkDomain(LinkDomain.of("test-room"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .stageType(StageType.GENERAL)
                .status(PartyroomStatus.ACTIVE)
                .build();
        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.of(partyroom));

        // when
        PartyroomData result = partyroomQueryService.getPartyroomById(partyroomId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Test Room");
    }

    @Test
    @DisplayName("getPartyroomById — 파티룸이 없으면 예외가 발생한다")
    void getPartyroomByIdNotFoundThrows() {
        // given
        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomQueryService.getPartyroomById(partyroomId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("isAlreadyRegistered — DJ로 등록되어 있으면 true를 반환한다")
    void isAlreadyRegisteredDjRegisteredReturnsTrue() {
        // given
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));
        when(aggregatePort.isDjRegistered(partyroomId, new CrewId(1L))).thenReturn(true);

        // when
        boolean result = partyroomQueryService.isAlreadyRegistered(partyroomId);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isAlreadyRegistered — 크루가 없으면 false를 반환한다")
    void isAlreadyRegisteredNoCrewReturnsFalse() {
        // given
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());

        // when
        boolean result = partyroomQueryService.isAlreadyRegistered(partyroomId);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("getCrewOrThrow — 크루가 없으면 예외가 발생한다")
    void getCrewOrThrowNoCrewThrows() {
        // given
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomQueryService.getCrewOrThrow(partyroomId, userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("getCrewOrThrow — 크루 행이 있어도 비활성이면 NOT_FOUND_ACTIVE_ROOM (WS 재연결 stale 멤버십 방어, #304)")
    void getCrewOrThrowInactiveCrewThrows() {
        // given: 크루 행은 존재하나 is_active=false (소프트 재연결 stale 윈도우)
        CrewData inactiveCrew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId)
                .gradeType(GradeType.CLUBBER).isActive(false).build();
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(inactiveCrew));

        // when & then: absent 와 동일하게 CRW-001 로 거부 (회전 유령 dj orphan 차단)
        assertThatThrownBy(() -> partyroomQueryService.getCrewOrThrow(partyroomId, userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("getSummaryInfo — 재생 비활성 시 DJ 정보가 null이다")
    void getSummaryInfoPlaybackInactiveDjIsNull() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId())
                .partyroomId(partyroomId)
                .title("Test Room")
                .introduction("Hello")
                .linkDomain(LinkDomain.of("test-room"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .stageType(StageType.GENERAL)
                .status(PartyroomStatus.ACTIVE)
                .build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);

        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.of(partyroom));
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);

        // when
        PartyroomSummaryResult result = partyroomQueryService.getSummaryInfo(partyroomId);

        // then
        assertThat(result.currentDj()).isNull();
        assertThat(result.title()).isEqualTo("Test Room");
    }

    @Test
    @DisplayName("getDjQueueInfo — DJ 큐 정보를 정상 반환한다")
    void getDjQueueInfoReturnsCorrectResult() {
        // given
        PartyroomData partyroom = PartyroomData.builder()
                .id(partyroomId.getId())
                .partyroomId(partyroomId)
                .title("Test Room")
                .introduction("Hello")
                .linkDomain(LinkDomain.of("test-room"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .stageType(StageType.GENERAL)
                .status(PartyroomStatus.ACTIVE)
                .build();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);

        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.of(partyroom));
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());

        // when
        DjQueueInfoResult result = partyroomQueryService.getDjQueueInfo(partyroomId);

        // then
        assertThat(result.playbackActivated()).isFalse();
        assertThat(result.queueStatus()).isEqualTo(QueueStatus.OPEN);
        assertThat(result.registered()).isFalse();
        assertThat(result.currentPlayback()).isNull();
        assertThat(result.djs()).isEmpty();
    }

    // ── Helper ──

    private PartyroomData buildPartyroom() {
        return PartyroomData.builder()
                .id(partyroomId.getId())
                .partyroomId(partyroomId)
                .title("Test Room")
                .introduction("Hello")
                .linkDomain(LinkDomain.of("test-room"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .stageType(StageType.GENERAL)
                .status(PartyroomStatus.ACTIVE)
                .build();
    }

    // ── getSummaryInfo — 재생 활성 분기 ──

    @Test
    @DisplayName("getSummaryInfo — 재생 활성 시 현재 DJ 정보가 포함된다")
    void getSummaryInfoPlaybackActiveIncludesDjInfo() {
        // given
        PartyroomData partyroom = buildPartyroom();
        PlaybackId playbackId = new PlaybackId(100L);
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        playbackState.activate(playbackId, new CrewId(1L));

        UserId djUserId = new UserId(50L);
        PlaybackData playback = mock(PlaybackData.class);
        when(playback.getUserId()).thenReturn(djUserId);

        CrewData djCrew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(djUserId).gradeType(GradeType.CLUBBER).build();

        ProfileSettingDto profileSetting = new ProfileSettingDto(
                "DJ_Nick", AvatarCompositionType.BODY_WITH_FACE, "body.png", "face.png", "icon.png",
                0, 0, 0.0, 0.0, 1.0);

        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.of(partyroom));
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(playbackQueryService.getPlaybackById(playbackId)).thenReturn(playback);
        when(aggregatePort.findCrew(partyroomId, djUserId)).thenReturn(Optional.of(djCrew));
        when(userProfileQueryPort.getUsersProfileSetting(Collections.singletonList(djUserId)))
                .thenReturn(Map.of(djUserId, profileSetting));

        // when
        PartyroomSummaryResult result = partyroomQueryService.getSummaryInfo(partyroomId);

        // then
        assertThat(result.currentDj()).isNotNull();
        assertThat(result.currentDj().nickname()).isEqualTo("DJ_Nick");
        assertThat(result.currentDj().avatarIconUri()).isEqualTo("icon.png");
        assertThat(result.title()).isEqualTo("Test Room");
    }

    // ── getProfileSummaryByCrewId ──

    @Test
    @DisplayName("getProfileSummaryByCrewId — 크루 ID로 프로필 요약 정보를 반환한다")
    void getProfileSummaryByCrewIdReturnsProfileSummary() {
        // given
        UserId targetUserId = new UserId(50L);
        Long crewId = 5L;

        ActivePartyroomDto activeDto = new ActivePartyroomDto(
                partyroomId.getId(), false, 1L, false, null, null);
        CrewData crew = CrewData.builder()
                .id(crewId).partyroomId(partyroomId).userId(targetUserId).gradeType(GradeType.CLUBBER).build();

        ProfileSummaryDto profileSummary = new ProfileSummaryDto(
                "Nick", "Hello!", "body.png", AvatarCompositionType.BODY_WITH_FACE,
                0, 0, 0.0, 0.0, 1.0, "face.png", "icon.png", null, List.of());

        when(queryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.of(activeDto));
        when(aggregatePort.findCrewById(crewId)).thenReturn(Optional.of(crew));
        when(userProfileQueryPort.getAuthorityTier(targetUserId)).thenReturn(AuthorityTier.FM);
        when(userProfileQueryPort.getOtherProfileSummary(targetUserId, AuthorityTier.FM))
                .thenReturn(profileSummary);

        // when
        CrewProfileSummaryResult result = partyroomQueryService.getProfileSummaryByCrewId(crewId);

        // then
        assertThat(result.crewId()).isEqualTo(crewId);
        assertThat(result.nickname()).isEqualTo("Nick");
        assertThat(result.introduction()).isEqualTo("Hello!");
        assertThat(result.avatarBodyUri()).isEqualTo("body.png");
    }

    @Test
    @DisplayName("getProfileSummaryByCrewId — 활성 파티룸이 없으면 예외가 발생한다")
    void getProfileSummaryByCrewIdNoActiveRoomThrows() {
        // given
        when(queryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomQueryService.getProfileSummaryByCrewId(5L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("getProfileSummaryByCrewId — 크루를 찾을 수 없으면 예외가 발생한다")
    void getProfileSummaryByCrewIdCrewNotFoundThrows() {
        // given
        ActivePartyroomDto activeDto = new ActivePartyroomDto(
                partyroomId.getId(), false, 1L, false, null, null);
        when(queryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.of(activeDto));
        when(aggregatePort.findCrewById(5L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomQueryService.getProfileSummaryByCrewId(5L))
                .isInstanceOf(NotFoundException.class);
    }

    // ── getPrimariesAvatarSettings ──

    @Test
    @DisplayName("getPrimariesAvatarSettings — 파티룸 크루의 아바타 설정을 반환한다")
    void getPrimariesAvatarSettingsReturnsAvatarSettings() {
        // given
        UserId user1 = new UserId(100L);
        UserId user2 = new UserId(101L);
        CrewDto crew1 = new CrewDto(1L, user1, GradeType.HOST);
        CrewDto crew2 = new CrewDto(2L, user2, GradeType.MODERATOR);

        PartyroomWithCrewDto dto = new PartyroomWithCrewDto(
                1L, StageType.GENERAL, user1, "Room", "Intro",
                false, false, 2L, null, List.of(crew1, crew2));

        ProfileSettingDto setting1 = new ProfileSettingDto(
                "Nick1", AvatarCompositionType.BODY_WITH_FACE, "body1.png", "face1.png", "icon1.png",
                0, 0, 0.0, 0.0, 1.0);
        ProfileSettingDto setting2 = new ProfileSettingDto(
                "Nick2", AvatarCompositionType.BODY_WITH_FACE, "body2.png", "face2.png", "icon2.png",
                0, 0, 0.0, 0.0, 1.0);

        when(userProfileQueryPort.getUsersProfileSetting(List.of(user1, user2)))
                .thenReturn(Map.of(user1, setting1, user2, setting2));

        // when
        Map<UserId, ProfileSettingDto> result =
                partyroomQueryService.getPrimariesAvatarSettings(List.of(dto));

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(user1).nickname()).isEqualTo("Nick1");
        assertThat(result.get(user2).nickname()).isEqualTo("Nick2");
    }

    // ── getDjQueueInfo — 재생 활성 분기 ──

    @Test
    @DisplayName("getDjQueueInfo — 재생 활성 시 현재 playback 정보가 포함된다")
    void getDjQueueInfoPlaybackActiveIncludesPlayback() {
        // given
        PartyroomData partyroom = buildPartyroom();
        PlaybackId playbackId = new PlaybackId(100L);
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        playbackState.activate(playbackId, new CrewId(1L));

        DjQueueData djQueue = DjQueueData.createFor(partyroomId);

        PlaybackData playback = mock(PlaybackData.class);

        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.of(partyroom));
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());
        when(playbackQueryService.getPlaybackById(playbackId)).thenReturn(playback);

        // when
        DjQueueInfoResult result = partyroomQueryService.getDjQueueInfo(partyroomId);

        // then
        assertThat(result.playbackActivated()).isTrue();
        assertThat(result.currentPlayback()).isEqualTo(playback);
        assertThat(result.queueStatus()).isEqualTo(QueueStatus.OPEN);
    }

    @Test
    @DisplayName("getDjQueueInfo — DJ 큐가 닫혀 있으면 CLOSE 상태를 반환한다")
    void getDjQueueInfoQueueClosedReturnsCloseStatus() {
        // given
        PartyroomData partyroom = buildPartyroom();
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        DjQueueData djQueue = DjQueueData.createFor(partyroomId);
        djQueue.close();

        when(aggregatePort.findPartyroomById(partyroomId.getId())).thenReturn(Optional.of(partyroom));
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(aggregatePort.findDjQueueState(partyroomId)).thenReturn(djQueue);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
        when(aggregatePort.findDjsOrdered(partyroomId)).thenReturn(Collections.emptyList());

        // when
        DjQueueInfoResult result = partyroomQueryService.getDjQueueInfo(partyroomId);

        // then
        assertThat(result.queueStatus()).isEqualTo(QueueStatus.CLOSE);
    }

    // ── getMyActivePartyroomOrThrow ──

    @Test
    @DisplayName("getMyActivePartyroomOrThrow — 활성 파티룸이 있으면 반환한다")
    void getMyActivePartyroomOrThrowFoundReturns() {
        // given
        ActivePartyroomDto activeDto = new ActivePartyroomDto(
                partyroomId.getId(), false, 1L, false, null, null);
        when(queryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.of(activeDto));

        // when
        ActivePartyroomDto result = partyroomQueryService.getMyActivePartyroomOrThrow(userId);

        // then
        assertThat(result.id()).isEqualTo(partyroomId.getId());
    }

    @Test
    @DisplayName("getMyActivePartyroomOrThrow — 활성 파티룸이 없으면 예외가 발생한다")
    void getMyActivePartyroomOrThrowNotFoundThrows() {
        // given
        when(queryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomQueryService.getMyActivePartyroomOrThrow(userId))
                .isInstanceOf(NotFoundException.class);
    }

    // ── getActiveCrews ──

    @Test
    @DisplayName("getActiveCrews — 활성 크루 목록을 반환한다")
    void getActiveCrewsReturnsActiveCrews() {
        // given
        CrewData crew1 = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(new UserId(10L)).gradeType(GradeType.CLUBBER).build();
        CrewData crew2 = CrewData.builder()
                .id(2L).partyroomId(partyroomId).userId(new UserId(20L)).gradeType(GradeType.LISTENER).build();
        when(aggregatePort.findActiveCrews(partyroomId)).thenReturn(List.of(crew1, crew2));

        // when
        List<CrewData> result = partyroomQueryService.getActiveCrews(partyroomId);

        // then
        assertThat(result).hasSize(2);
    }

    // ── getCrewByUserId ──

    @Test
    @DisplayName("getCrewByUserId — 크루가 존재하면 Optional로 반환한다")
    void getCrewByUserIdFoundReturnsOptional() {
        // given
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));

        // when
        Optional<CrewData> result = partyroomQueryService.getCrewByUserId(partyroomId, userId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getCrewByUserId — 크루가 없으면 빈 Optional을 반환한다")
    void getCrewByUserIdNotFoundReturnsEmpty() {
        // given
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());

        // when
        Optional<CrewData> result = partyroomQueryService.getCrewByUserId(partyroomId, userId);

        // then
        assertThat(result).isEmpty();
    }

    // ── getMyActivePartyroom ──

    @Test
    @DisplayName("getMyActivePartyroom — ThreadLocal에서 userId를 가져와 활성 파티룸을 반환한다")
    void getMyActivePartyroomNoArgReturnsFromThreadLocal() {
        // given
        ActivePartyroomDto activeDto = new ActivePartyroomDto(
                partyroomId.getId(), false, 1L, false, null, null);
        when(queryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.of(activeDto));

        // when
        Optional<ActivePartyroomDto> result = partyroomQueryService.getMyActivePartyroom();

        // then
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(partyroomId.getId());
    }

    // ── getCrewOrThrow — 성공 케이스 ──

    @Test
    @DisplayName("getCrewOrThrow — 활성 크루가 존재하면 반환한다")
    void getCrewOrThrowFoundReturns() {
        // given
        CrewData crew = CrewData.builder()
                .id(1L).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).isActive(true).build();
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));

        // when
        CrewData result = partyroomQueryService.getCrewOrThrow(partyroomId, userId);

        // then
        assertThat(result.getId()).isEqualTo(1L);
    }

    // ── getCurrentPlaybackLinkId ──

    @Test
    @DisplayName("getCurrentPlaybackLinkId — 재생 활성 시 현재 곡의 linkId 를 반환한다")
    void getCurrentPlaybackLinkId_활성_시_linkId_반환() {
        // given
        PlaybackId playbackId = new PlaybackId(200L);
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        playbackState.activate(playbackId, new CrewId(1L));

        PlaybackData playback = mock(PlaybackData.class);
        when(playback.getLinkId()).thenReturn("youtube_abc123");

        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);
        when(playbackQueryService.getPlaybackById(playbackId)).thenReturn(playback);

        // when
        String result = partyroomQueryService.getCurrentPlaybackLinkId(partyroomId);

        // then
        assertThat(result).isEqualTo("youtube_abc123");
    }

    @Test
    @DisplayName("getCurrentPlaybackLinkId — 재생 비활성 시 null 을 반환한다")
    void getCurrentPlaybackLinkId_비활성_시_null_반환() {
        // given
        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);
        // 활성화 안 함 → isActivated() == false

        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);

        // when
        String result = partyroomQueryService.getCurrentPlaybackLinkId(partyroomId);

        // then
        assertThat(result).isNull();
    }
}
