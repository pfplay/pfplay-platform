package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.application.service.chat.PartyroomChatCommandService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.config.VirtualCrewChatAsyncConfig;
import com.pfplaybackend.api.virtualcrew.application.port.BotChatDispatcher;
import com.pfplaybackend.api.virtualcrew.application.port.LlmChatProvider;
import com.pfplaybackend.api.virtualcrew.application.port.PersonaQueryPort;
import com.pfplaybackend.api.virtualcrew.application.port.RoomContextReader;
import com.pfplaybackend.api.virtualcrew.application.port.RoomContextReader.RoomContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * {@link BotChatDispatcher} 의 실제 LLM 워커 구현 (Chunk 5).
 *
 * <p>게이트({@code BotChatTrigger})가 확률·쿨다운·후보 선정을 끝내고 {@link #dispatch} 를 호출하면,
 * 전용 스레드풀({@link VirtualCrewChatAsyncConfig#VCREW_CHAT_EXECUTOR})에서 페르소나 지시문 조회 →
 * 방 컨텍스트·최근 대화 조립 → LLM 호출 → 송신을 수행한다. 즉시 반환하며 실제 작업은 비동기다.
 *
 * <p>이 {@code @Service} 가 빈 컨테이너에 등록되면 {@code BotChatDispatcherConfig} 의
 * {@code @ConditionalOnMissingBean} 폴백(NoOp)은 자동으로 backoff 된다(BotChatDispatcher 빈 정확히 1개).
 *
 * <h2>lock/cooldown 비취급</h2>
 * 게이트가 SETNX 키 TTL 로 동시성·쿨다운을 소유한다. 이 워커는 어떤 lock 도 만지지 않으며,
 * 실패/드롭은 단지 한 번의 턴을 거른 것이다(TTL 만료로 다음 턴 자연 허용).
 *
 * <h2>드롭 사슬</h2>
 * <ol>
 *   <li>페르소나 매핑 없음 → 드롭</li>
 *   <li>LLM 응답이 빈 문자열/공백 → 드롭(best-effort 계약)</li>
 *   <li>봇이 더 이상 active crew 아님(방 떠남) → 드롭</li>
 * </ol>
 * 어떤 예외도 {@link #dispatch} 밖으로 전파하지 않는다(warn 로깅 후 흡수).
 */
@Slf4j
@Service
public class LlmChatTaskRunner implements BotChatDispatcher {

    private final Executor executor;
    private final ChatPromptAssembler assembler;
    private final RoomContextReader roomContextReader;
    private final ChatContextBuffer buffer;
    private final PersonaQueryPort personaQuery;
    private final LlmChatProvider provider;
    private final PartyroomChatCommandService chatCommandService;
    private final CrewRepository crewRepository;
    private final VirtualCrewChatConfig config;

    public LlmChatTaskRunner(@Qualifier(VirtualCrewChatAsyncConfig.VCREW_CHAT_EXECUTOR) Executor executor,
                             ChatPromptAssembler assembler,
                             RoomContextReader roomContextReader,
                             ChatContextBuffer buffer,
                             PersonaQueryPort personaQuery,
                             LlmChatProvider provider,
                             PartyroomChatCommandService chatCommandService,
                             CrewRepository crewRepository,
                             VirtualCrewChatConfig config) {
        this.executor = executor;
        this.assembler = assembler;
        this.roomContextReader = roomContextReader;
        this.buffer = buffer;
        this.personaQuery = personaQuery;
        this.provider = provider;
        this.chatCommandService = chatCommandService;
        this.crewRepository = crewRepository;
        this.config = config;
    }

    @Override
    public void dispatch(PartyroomId partyroomId, long botCrewId, long botUserId) {
        executor.execute(() -> {
            try {
                String instruction = personaQuery.instructionOf(botUserId);
                if (instruction == null) return;                              // 매핑 제거됨 → 드롭
                RoomContext ctx = roomContextReader.read(partyroomId);
                String system = assembler.assembleSystem(instruction, ctx);
                String userContent = assembler.assembleUserContent(buffer.recent(partyroomId, config.contextSize()));
                String reply = provider.complete(system, userContent, config.outputMaxTokens());
                if (reply == null || reply.isBlank()) return;                 // 빈 출력 드롭
                if (!isStillActiveCrew(partyroomId, botUserId)) return;        // 봇 퇴장 드롭
                chatCommandService.sendMessageAsCrew(partyroomId, botCrewId, reply.trim());
            } catch (Exception e) {
                log.warn("vcrew chat dispatch failed (room={}): {}", partyroomId, e.getMessage());
            }
        });
    }

    private boolean isStillActiveCrew(PartyroomId partyroomId, long botUserId) {
        return crewRepository.findByPartyroomIdAndUserId(partyroomId, new UserId(botUserId))
                .map(CrewData::isActive)
                .orElse(false);
    }
}
