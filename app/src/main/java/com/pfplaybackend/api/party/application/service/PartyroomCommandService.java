package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.command.CreatePartyroomCommand;
import com.pfplaybackend.api.party.application.dto.command.UpdateDjQueueStatusCommand;
import com.pfplaybackend.api.party.application.dto.command.UpdatePartyroomCommand;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.QueueStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.policy.PartyroomCreationPolicy;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartyroomCommandService {

    private static final int LINK_DOMAIN_GENERATION_MAX_ATTEMPTS = 5;

    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void createMainStage(CreatePartyroomCommand command, UserId adminId) {
        // 도메인 invariant: 프로필 없는 사용자는 partyroom에 active crew로 등록하지 않는다.
        // V5-seeded super-admin은 profile이 없으므로 enterByHost를 호출하면 customer GET /api/v1/partyrooms
        // 응답 빌드 시 ProfileSettingDto null lookup → NPE. 호스트 권한은 partyroom.host_id로 충분하며
        // 본 스테이지엔 crew row가 불필요. (PA-7)
        createPartyroom(command, StageType.MAIN, adminId);
    }

    @Transactional
    public PartyroomData createGeneralPartyRoom(CreatePartyroomCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        new PartyroomCreationPolicy().enforce(authContext.getAuthorityTier());
        Optional<PartyroomData> optionalActive = aggregatePort.findNonTerminatedHostRoom(authContext.getUserId());
        if(optionalActive.isPresent()) throw ExceptionCreator.create(PartyroomException.ALREADY_HOST);

        String linkDomain = command.linkDomain();
        if (linkDomain == null || linkDomain.isEmpty()) {
            linkDomain = generateUniqueLinkDomain();
        } else if (aggregatePort.findByLinkDomain(LinkDomain.of(linkDomain)).isPresent()) {
            throw ExceptionCreator.create(PartyroomException.LINK_DOMAIN_ALREADY_EXISTS);
        }
        PartyroomData createdPartyroom = createPartyroom(
                new CreatePartyroomCommand(command.title(), command.introduction(), linkDomain, command.playbackTimeLimit()),
                StageType.GENERAL, authContext.getUserId());
        partyroomAccessCommandService.enterByHost(authContext.getUserId(), createdPartyroom);
        return createdPartyroom;
    }

    private String generateUniqueLinkDomain() {
        for (int attempt = 0; attempt < LINK_DOMAIN_GENERATION_MAX_ATTEMPTS; attempt++) {
            String candidate = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
            if (aggregatePort.findByLinkDomain(LinkDomain.of(candidate)).isEmpty()) {
                return candidate;
            }
        }
        throw ExceptionCreator.create(PartyroomException.LINK_DOMAIN_ALREADY_EXISTS);
    }

    private PartyroomData createPartyroom(CreatePartyroomCommand command, StageType stageType, UserId hostId) {
        PartyroomData partyroom = PartyroomData.create(
                command.title(), command.introduction(),
                LinkDomain.of(command.linkDomain()),
                PlaybackTimeLimit.ofMinutes(command.playbackTimeLimit()),
                stageType, hostId);
        PartyroomData saved = aggregatePort.savePartyroom(partyroom);
        aggregatePort.savePlaybackState(PartyroomPlaybackData.createFor(saved.getPartyroomId()));
        aggregatePort.saveDjQueueState(DjQueueData.createFor(saved.getPartyroomId()));

        // PR 12a — UserActivityLogListener consumes this for PARTYROOM_CREATED row.
        // spec §5.2 "service 코드 변경 0" 가정은 부정확 — Chunk 7 §12 catch-up.
        eventPublisher.publishEvent(new PartyroomCreatedEvent(
                saved.getPartyroomId(), hostId.getUid(), saved.getStageType()));

        return saved;
    }

    @Transactional
    public void updatePartyroom(PartyroomId partyroomId, UpdatePartyroomCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        partyroom.validateHost(authContext.getUserId());
        partyroom.updateBaseInfo(command.title(), command.introduction(),
                LinkDomain.of(command.linkDomain()),
                PlaybackTimeLimit.ofMinutes(command.playbackTimeLimit()));
        aggregatePort.savePartyroom(partyroom);
    }

    @Transactional
    public void deletePartyRoom(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        if (authContext.getAuthorityTier() != AuthorityTier.FM) {
            throw ExceptionCreator.create(PartyroomException.RESTRICTED_AUTHORITY);
        }
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        partyroom.terminate();
        aggregatePort.savePartyroom(partyroom);
        partyroom.pollDomainEvents().forEach(eventPublisher::publishEvent);
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteUnusedPartyroom() {
        List<PartyroomData> unusedPartyroomDataList = aggregatePort.findAllUnusedPartyroomDataByDay(30);
        unusedPartyroomDataList.forEach(partyroom -> {
            partyroom.terminate();
            aggregatePort.savePartyroom(partyroom);
            partyroom.pollDomainEvents().forEach(eventPublisher::publishEvent);
        });
    }

    @Transactional
    public void updateDjQueueStatus(PartyroomId partyroomId, UpdateDjQueueStatusCommand command) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        partyroom.validateHost(authContext.getUserId());
        DjQueueData djQueue = aggregatePort.findDjQueueState(partyroomId);
        if (command.queueStatus().equals(QueueStatus.CLOSE)) djQueue.close();
        if (command.queueStatus().equals(QueueStatus.OPEN)) djQueue.open();
        aggregatePort.saveDjQueueState(djQueue);
    }

    public void initializeMainStage(UserId adminId) {
        CreatePartyroomCommand command = new CreatePartyroomCommand(
                "Main Stage",
                "Welcome to the main stage",
                "main",
                10);
        if (aggregatePort.findByLinkDomain(LinkDomain.of(command.linkDomain())).isPresent()) {
            return;
        }
        createMainStage(command, adminId);
    }
}
