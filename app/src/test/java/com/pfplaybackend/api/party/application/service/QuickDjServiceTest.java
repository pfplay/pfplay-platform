package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Quick-DJ(#331) 오케스트레이션 — 시간한도 사전검증(결정7/B)·TEMP 준비·ONE_SHOT enqueue.
 */
@ExtendWith(MockitoExtension.class)
class QuickDjServiceTest {

    @Mock PartyroomQueryService partyroomQueryService;
    @Mock PlaylistCommandPort playlistCommandPort;
    @Mock DjCommandService djCommandService;

    @InjectMocks QuickDjService quickDjService;

    private final UserId userId = new UserId(1L);
    private final PartyroomId partyroomId = new PartyroomId(10L);

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    private PartyroomData roomWithLimitMinutes(int minutes) {
        return PartyroomData.builder().id(partyroomId.getId()).partyroomId(partyroomId)
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(minutes)).build();
    }

    private AddTrackCommand command(String duration) {
        return new AddTrackCommand("곡A", "aaa111", duration, "https://i.ytimg.com/vi/aaa111/mqdefault.jpg");
    }

    @Test
    @DisplayName("곡 duration > 방 한도 → DJ-007, write 미발생")
    void rejectsTrackExceedingTimeLimit() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(roomWithLimitMinutes(3));
        when(partyroomQueryService.getCrewOrThrow(eq(partyroomId), any())).thenReturn(mock(CrewData.class));

        // ExceptionCreator 는 getMessage()에 errorCode 를 싣지 않는다 — 타입 + errorCode 필드로 단언
        assertThatThrownBy(() -> quickDjService.quickEnqueue(partyroomId, command("3:01")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "DJ-007");

        verifyNoInteractions(playlistCommandPort);
        verifyNoInteractions(djCommandService);
    }

    @Test
    @DisplayName("happy — TEMP 준비 후 ONE_SHOT 으로 enqueue")
    void happyPathPreparesTempAndEnqueuesOneShot() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(roomWithLimitMinutes(5));
        when(partyroomQueryService.getCrewOrThrow(eq(partyroomId), any())).thenReturn(mock(CrewData.class));
        when(playlistCommandPort.prepareOneShotPlaylist(eq(userId), any(AddTrackCommand.class))).thenReturn(42L);
        DjData saved = DjData.create(partyroomId, new PlaylistId(42L), new CrewId(3L), 2, DjKind.ONE_SHOT);
        when(djCommandService.enqueueDj(partyroomId, new PlaylistId(42L), DjKind.ONE_SHOT)).thenReturn(saved);

        DjData result = quickDjService.quickEnqueue(partyroomId, command("3:45"));

        assertThat(result.getKind()).isEqualTo(DjKind.ONE_SHOT);
        assertThat(result.getOrderNumber()).isEqualTo(2);
        InOrder inOrder = inOrder(playlistCommandPort, djCommandService);
        inOrder.verify(playlistCommandPort).prepareOneShotPlaylist(eq(userId), any(AddTrackCommand.class));
        inOrder.verify(djCommandService).enqueueDj(partyroomId, new PlaylistId(42L), DjKind.ONE_SHOT);
    }

    @Test
    @DisplayName("무제한 방(limit=0) — 어떤 duration 도 통과")
    void unlimitedRoomAcceptsAnyDuration() {
        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(roomWithLimitMinutes(0));
        when(partyroomQueryService.getCrewOrThrow(eq(partyroomId), any())).thenReturn(mock(CrewData.class));
        when(playlistCommandPort.prepareOneShotPlaylist(eq(userId), any())).thenReturn(42L);
        when(djCommandService.enqueueDj(eq(partyroomId), any(), eq(DjKind.ONE_SHOT)))
                .thenReturn(DjData.create(partyroomId, new PlaylistId(42L), new CrewId(3L), 1, DjKind.ONE_SHOT));

        quickDjService.quickEnqueue(partyroomId, command("2:10:00"));

        verify(djCommandService).enqueueDj(eq(partyroomId), any(), eq(DjKind.ONE_SHOT));
    }
}
