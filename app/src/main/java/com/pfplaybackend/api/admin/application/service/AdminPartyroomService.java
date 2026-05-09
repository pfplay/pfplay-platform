package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.dto.command.AdminCreatePartyroomCommand;
import com.pfplaybackend.api.admin.application.dto.command.BulkPreviewCommand;
import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomResult;
import com.pfplaybackend.api.admin.application.dto.result.BulkPreviewResult;
import com.pfplaybackend.api.admin.application.dto.result.SimulateReactionsResult;
import com.pfplaybackend.api.admin.application.port.out.AdminPartyroomPort;
import com.pfplaybackend.api.admin.domain.exception.AdminException;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.application.service.PlaybackQueryService;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.*;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Admin service for managing partyrooms
 * Provides admin-specific partyroom operations including bulk creation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPartyroomService {

    private final AdminPartyroomPort adminPartyroomPort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final AdminUserService adminUserService;
    private final PlaybackQueryService playbackQueryService;
    private final ReactionSimulationService reactionSimulationService;
    private final Clock clock;
    private final ExecutorService reactionSimulationExecutor;

    @Transactional
    public AdminPartyroomResult createPartyroomWithHost(AdminCreatePartyroomCommand command) {
        UserId hostUserId = parseUserId(command.hostUserId());

        String linkDomain = command.linkDomain();
        if (linkDomain == null || linkDomain.isEmpty()) {
            linkDomain = generateUniqueLinkDomain();
        } else if (isLinkDomainDuplicated(linkDomain)) {
            throw ExceptionCreator.create(PartyroomException.LINK_DOMAIN_ALREADY_EXISTS);
        }

        PartyroomData partyroom = createPartyroom(
                command.title(),
                command.introduction() != null ? command.introduction() : "",
                linkDomain,
                command.playbackTimeLimit(),
                StageType.GENERAL, hostUserId);
        partyroomAccessCommandService.enterByHost(hostUserId, partyroom);

        log.info("Admin created partyroom: partyroomId={}, hostUserId={}, title={}",
                partyroom.getPartyroomId().getId(), hostUserId.getUid(), partyroom.getTitle());

        return AdminPartyroomResult.from(partyroom, hostUserId.getUid().toString());
    }

    @Transactional
    public BulkPreviewResult createBulkPreviewEnvironment(BulkPreviewCommand command) {
        long startTime = clock.millis();

        List<BulkPreviewResult.PartyroomSummary> partyroomSummaries = new ArrayList<>();
        int totalVirtualMembers = 0;

        log.info("Starting bulk preview environment creation: {} partyrooms with {} users each",
                command.partyroomCount(), command.usersPerRoom());

        for (int i = 1; i <= command.partyroomCount(); i++) {
            String title = String.format("%s %d", command.titlePrefix(), i);
            String linkDomain = generateLinkDomainForBulk(command.linkDomainPrefix(), i);

            List<MemberData> virtualMembers = new ArrayList<>();
            for (int j = 0; j < command.usersPerRoom(); j++) {
                String nickname = String.format("Virtual_%d_%d", i, j + 1);
                MemberData member = adminUserService.createVirtualMember(nickname, null, null);
                virtualMembers.add(member);
                totalVirtualMembers++;
            }

            UserId hostUserId = new UserId(virtualMembers.get(0).getUserAccountId());

            PartyroomData partyroom = createPartyroom(
                    title,
                    command.introduction() != null ? command.introduction() : "Preview environment room",
                    linkDomain,
                    command.playbackTimeLimit(),
                    StageType.GENERAL, hostUserId);
            partyroomAccessCommandService.enterByHost(hostUserId, partyroom);

            for (int j = 1; j < virtualMembers.size(); j++) {
                MemberData member = virtualMembers.get(j);
                enterMemberAsRegularCrew(partyroom, new UserId(member.getUserAccountId()));
            }

            List<String> crewUserIds = virtualMembers.stream()
                    .map(m -> m.getUserAccountId().toString())
                    .collect(Collectors.toList());

            BulkPreviewResult.PartyroomSummary summary = new BulkPreviewResult.PartyroomSummary(
                    partyroom.getPartyroomId().getId(),
                    partyroom.getTitle(),
                    partyroom.getLinkDomain().getValue(),
                    hostUserId.getUid().toString(),
                    virtualMembers.size(),
                    crewUserIds);

            partyroomSummaries.add(summary);

            log.info("Created partyroom {}/{}: partyroomId={}, hostUserId={}, crewCount={}",
                    i, command.partyroomCount(),
                    partyroom.getPartyroomId().getId(),
                    hostUserId.getUid(),
                    virtualMembers.size());
        }

        long executionTime = clock.millis() - startTime;

        log.info("Bulk preview environment created: {} partyrooms, {} virtual members, {}ms",
                command.partyroomCount(), totalVirtualMembers, executionTime);

        return new BulkPreviewResult(
                command.partyroomCount(),
                totalVirtualMembers,
                executionTime,
                partyroomSummaries);
    }

    private PartyroomData createPartyroom(String title, String introduction, String linkDomain,
                                          int playbackTimeLimit, StageType stageType, UserId hostId) {
        PartyroomData partyroom = PartyroomData.create(
                title, introduction,
                LinkDomain.of(linkDomain),
                PlaybackTimeLimit.ofMinutes(playbackTimeLimit),
                stageType, hostId);
        return adminPartyroomPort.savePartyroom(partyroom);
    }

    private void enterMemberAsRegularCrew(PartyroomData partyroom, UserId userId) {
        PartyroomData loadedPartyroom = adminPartyroomPort.findPartyroomById(partyroom.getPartyroomId().getId())
                .orElseThrow();

        CrewData crew = CrewData.create(loadedPartyroom.getPartyroomId(), userId, GradeType.LISTENER, null, LocalDateTime.now(clock));
        adminPartyroomPort.saveCrew(crew);
    }

    private String generateUniqueLinkDomain() {
        String linkDomain;
        do {
            linkDomain = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
        } while (isLinkDomainDuplicated(linkDomain));
        return linkDomain;
    }

    private String generateLinkDomainForBulk(String prefix, int index) {
        if (prefix == null || prefix.isEmpty()) {
            return String.format("preview_%d_%s", index,
                    UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8));
        }
        return String.format("%s_%d", prefix, index);
    }

    private boolean isLinkDomainDuplicated(String linkDomain) {
        return adminPartyroomPort.findPartyroomByLinkDomain(LinkDomain.of(linkDomain)).isPresent();
    }

    private UserId parseUserId(String userIdString) {
        try {
            Long id = Long.parseLong(userIdString);
            return new UserId(id);
        } catch (NumberFormatException e) {
            throw ExceptionCreator.create(AdminException.INVALID_USER_ID_FORMAT);
        }
    }

    public SimulateReactionsResult simulateReactions(Long partyroomId) {
        PartyroomData partyroom = adminPartyroomPort.findPartyroomById(partyroomId)
                .orElseThrow(() -> ExceptionCreator.create(AdminException.PARTYROOM_NOT_FOUND));
        PartyroomPlaybackData playbackState = adminPartyroomPort.findPlaybackState(new PartyroomId(partyroomId)).orElseThrow();

        PlaybackId playbackId = playbackState.getCurrentPlaybackId();
        if (playbackId == null) {
            throw ExceptionCreator.create(AdminException.NO_ACTIVE_PLAYBACK);
        }

        PlaybackData playback = playbackQueryService.getPlaybackById(playbackId);
        UserId djUserId = playback.getUserId();

        List<CrewData> eligibleCrew = adminPartyroomPort.findActiveCrewByPartyroom(new PartyroomId(partyroomId)).stream()
                .filter(crew -> !crew.getUserId().equals(djUserId))
                .collect(Collectors.toList());

        if (eligibleCrew.isEmpty()) {
            throw ExceptionCreator.create(AdminException.NO_AVAILABLE_CREW);
        }

        Collections.shuffle(eligibleCrew);
        int selectionCount = Math.max(1, (int) Math.ceil(eligibleCrew.size() * 0.7));
        List<CrewData> selectedCrew = eligibleCrew.subList(0, selectionCount);

        log.info("Selected {}/{} crew members for reaction simulation", selectionCount, eligibleCrew.size());

        Collections.shuffle(selectedCrew);
        int halfCount = selectedCrew.size() / 2;
        List<CrewData> likeGroup = selectedCrew.subList(0, halfCount);
        List<CrewData> grabGroup = selectedCrew.subList(halfCount, selectedCrew.size());

        List<CompletableFuture<SimulateReactionsResult.SimulatedReaction>> reactionFutures = new ArrayList<>();

        for (CrewData crew : likeGroup) {
            CompletableFuture<SimulateReactionsResult.SimulatedReaction> future = CompletableFuture.supplyAsync(() -> {
                int delayMs = ThreadLocalRandom.current().nextInt(0, 5001);
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Reaction simulation interrupted", e);
                }
                return reactionSimulationService.simulateReaction(
                        crew.getUserId(),
                        new CrewId(crew.getId()),
                        playbackId,
                        new PartyroomId(partyroomId),
                        ReactionType.LIKE,
                        delayMs
                );
            }, reactionSimulationExecutor);
            reactionFutures.add(future);
        }

        for (CrewData crew : grabGroup) {
            CompletableFuture<SimulateReactionsResult.SimulatedReaction> future = CompletableFuture.supplyAsync(() -> {
                int delayMs = ThreadLocalRandom.current().nextInt(0, 5001);
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Reaction simulation interrupted", e);
                }
                return reactionSimulationService.simulateReaction(
                        crew.getUserId(),
                        new CrewId(crew.getId()),
                        playbackId,
                        new PartyroomId(partyroomId),
                        ReactionType.GRAB,
                        delayMs
                );
            }, reactionSimulationExecutor);
            reactionFutures.add(future);
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                reactionFutures.toArray(new CompletableFuture[0])
        );

        try {
            allFutures.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Error waiting for reaction simulation to complete", e);
            throw ExceptionCreator.create(AdminException.REACTION_SIMULATION_FAILED);
        }

        List<SimulateReactionsResult.SimulatedReaction> reactions = reactionFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        PlaybackAggregationData aggregation = adminPartyroomPort.findPlaybackAggregation(playbackId).orElseThrow();

        log.info("Simulated reactions completed: partyroomId={}, playbackId={}, reactions={}, likes={}, grabs={}",
                partyroomId, playbackId.getId(), reactions.size(), likeGroup.size(), grabGroup.size());

        return new SimulateReactionsResult(
                partyroomId,
                playbackId.getId(),
                reactions,
                new SimulateReactionsResult.AggregationCounts(
                        aggregation.getLikeCount(),
                        aggregation.getDislikeCount(),
                        aggregation.getGrabCount()));
    }
}
