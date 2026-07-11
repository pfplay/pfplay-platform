package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.exception.DjException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-DJ(#331) — 곡 즉석 선택 → one-shot DJ 큐 등록 오케스트레이션.
 * 시간한도 사전검증(결정7/B) → TEMP 플리 준비(리셋+삽입) → ONE_SHOT enqueue 를
 * 1 트랜잭션으로 묶어, 실패 시 TEMP 에 곡만 남는 중간상태를 남기지 않는다(spec §3-2).
 * 양자택일(이미 큐 등록 시 거부)은 enqueue 내부의 ALREADY_REGISTERED 가드가 그대로 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickDjService {

    private final PartyroomQueryService partyroomQueryService;
    private final PlaylistCommandPort playlistCommandPort;
    private final DjCommandService djCommandService;

    @Transactional
    public DjData quickEnqueue(PartyroomId partyroomId, AddTrackCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        log.info("[quickEnqueue] ENTER - requestId={}, partyroomId={}, userId={}, linkId={}",
                RequestIdInterceptor.current(), partyroomId.getId(), userId.getUid(), command.linkId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        partyroomQueryService.getCrewOrThrow(partyroomId, userId);

        Duration duration = Duration.fromString(command.duration());
        if (partyroom.getPlaybackTimeLimit().exceedsDuration(duration)) {
            throw ExceptionCreator.create(DjException.TRACK_EXCEEDS_TIME_LIMIT);
        }

        Long tempPlaylistId = playlistCommandPort.prepareOneShotPlaylist(userId, command);
        return djCommandService.enqueueDj(partyroomId, new PlaylistId(tempPlaylistId), DjKind.ONE_SHOT);
    }
}
