package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.application.service.chat.PartyroomChatCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.port.LlmChatProvider;
import com.pfplaybackend.api.virtualcrew.application.port.PersonaQueryPort;
import com.pfplaybackend.api.virtualcrew.application.port.RoomContextReader;
import com.pfplaybackend.api.virtualcrew.application.port.RoomContextReader.RoomContext;
import com.pfplaybackend.api.virtualcrew.application.service.ChatContextBuffer;
import com.pfplaybackend.api.virtualcrew.application.service.ChatPromptAssembler;
import com.pfplaybackend.api.virtualcrew.application.service.LlmChatTaskRunner;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewChatConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmChatTaskRunner} 단위 테스트 — 비동기 본문의 드롭 사슬과 송신을 검증한다.
 *
 * <p>동기 실행 stub({@code Runnable::run})을 executor 로 주입해 {@code dispatch} 본문이
 * 결정적으로 즉시 실행되게 한다. 나머지 협력자는 모두 mock.
 */
@ExtendWith(MockitoExtension.class)
class LlmChatTaskRunnerTest {

    /** 제출된 Runnable 을 같은 스레드에서 즉시 실행하는 동기 executor. */
    private static final Executor SYNC_EXECUTOR = Runnable::run;

    @Mock private ChatPromptAssembler assembler;
    @Mock private RoomContextReader roomContextReader;
    @Mock private ChatContextBuffer buffer;
    @Mock private PersonaQueryPort personaQuery;
    @Mock private LlmChatProvider provider;
    @Mock private PartyroomChatCommandService chatCommandService;
    @Mock private CrewRepository crewRepository;
    @Mock private VirtualCrewChatConfig config;

    private LlmChatTaskRunner runner;

    private static final long ROOM_ID = 555L;
    private static final long BOT_CREW_ID = 43L;
    private static final long BOT_USER_ID = 7002L;
    private static final PartyroomId ROOM = new PartyroomId(ROOM_ID);

    @BeforeEach
    void setUp() {
        runner = new LlmChatTaskRunner(SYNC_EXECUTOR, assembler, roomContextReader, buffer,
                personaQuery, provider, chatCommandService, crewRepository, config);
        lenient().when(config.contextSize()).thenReturn(20);
        lenient().when(config.outputMaxTokens()).thenReturn(256);
    }

    private void stubFullPipeline(String llmReply) {
        when(personaQuery.instructionOf(BOT_USER_ID)).thenReturn("차분한 톤");
        when(roomContextReader.read(ROOM)).thenReturn(new RoomContext("방제목", null, null));
        when(buffer.recent(eq(ROOM), anyInt())).thenReturn(List.of("안녕하세요"));
        when(assembler.assembleSystem(anyString(), any())).thenReturn("system");
        when(assembler.assembleUserContent(any())).thenReturn("user");
        when(provider.complete(anyString(), anyString(), anyInt())).thenReturn(llmReply);
    }

    private void stubActiveCrew(boolean active) {
        CrewData crew = mock(CrewData.class);
        lenient().when(crew.isActive()).thenReturn(active);
        when(crewRepository.findByPartyroomIdAndUserId(eq(ROOM), eq(new UserId(BOT_USER_ID))))
                .thenReturn(Optional.of(crew));
    }

    @Test
    @DisplayName("happy path: 지시문 있음 + 응답 '안녕' + active crew → sendMessageAsCrew 1회")
    void happyPath_sendsReply() {
        stubFullPipeline("안녕");
        stubActiveCrew(true);

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, times(1)).sendMessageAsCrew(ROOM, BOT_CREW_ID, "안녕");
    }

    @Test
    @DisplayName("지시문 null(매핑 없음) → send 0, provider.complete 0")
    void instructionNull_drops() {
        when(personaQuery.instructionOf(BOT_USER_ID)).thenReturn(null);

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(provider, never()).complete(anyString(), anyString(), anyInt());
        verify(chatCommandService, never()).sendMessageAsCrew(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("응답 빈 문자열 → send 0")
    void emptyReply_drops() {
        stubFullPipeline("");

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, never()).sendMessageAsCrew(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("응답 공백만 → send 0")
    void blankReply_drops() {
        stubFullPipeline("   ");

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, never()).sendMessageAsCrew(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("봇이 더 이상 active crew 아님(inactive) → send 0")
    void botInactiveCrew_drops() {
        stubFullPipeline("안녕");
        stubActiveCrew(false);

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, never()).sendMessageAsCrew(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("봇 crew row 없음(빈 Optional) → send 0")
    void botCrewMissing_drops() {
        stubFullPipeline("안녕");
        when(crewRepository.findByPartyroomIdAndUserId(eq(ROOM), eq(new UserId(BOT_USER_ID))))
                .thenReturn(Optional.empty());

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, never()).sendMessageAsCrew(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("provider 예외 → dispatch 밖으로 전파 안 함, send 0")
    void providerThrows_swallowedNoPropagate() {
        when(personaQuery.instructionOf(BOT_USER_ID)).thenReturn("차분한 톤");
        when(roomContextReader.read(ROOM)).thenReturn(new RoomContext("방제목", null, null));
        when(buffer.recent(eq(ROOM), anyInt())).thenReturn(List.of("안녕하세요"));
        when(assembler.assembleSystem(anyString(), any())).thenReturn("system");
        when(assembler.assembleUserContent(any())).thenReturn("user");
        when(provider.complete(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("LLM 폭발"));

        // 예외가 전파되면 이 호출이 실패한다 — 전파 안 함을 검증.
        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, never()).sendMessageAsCrew(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("응답 앞뒤 공백 → trim 후 송신")
    void replyWithWhitespace_sentTrimmed() {
        stubFullPipeline("  반가워요  ");
        stubActiveCrew(true);

        runner.dispatch(ROOM, BOT_CREW_ID, BOT_USER_ID);

        verify(chatCommandService, times(1)).sendMessageAsCrew(ROOM, BOT_CREW_ID, "반가워요");
    }
}
