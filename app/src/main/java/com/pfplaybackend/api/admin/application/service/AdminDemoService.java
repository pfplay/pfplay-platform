package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.dto.command.InitializeDemoCommand;
import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomListResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoEnvironmentResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoStatusResult;
import com.pfplaybackend.api.admin.application.port.out.AdminAvatarResourcePort;
import com.pfplaybackend.api.admin.application.port.out.AdminMemberPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPartyroomPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPlaylistPort;
import com.pfplaybackend.api.admin.application.util.NicknameGenerator;
import com.pfplaybackend.api.admin.domain.DemoTrackConstants;
import com.pfplaybackend.api.admin.domain.exception.AdminException;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.user.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.user.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.user.domain.value.AvatarFaceUri;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Admin service for initializing complete demo environment
 * <p>
 * Creates full demo setup with:
 * - 410 virtual members (13 special + 397 regular)
 * - 12 general partyrooms (main stage already exists from app initialization)
 * - Automatic room entry and DJ queue registration
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDemoService {

    private final AdminUserService adminUserService;
    private final AdminMemberPort adminMemberPort;
    private final AdminAvatarResourcePort adminAvatarResourcePort;
    private final AdminPlaylistPort adminPlaylistPort;
    private final AdminPartyroomPort adminPartyroomPort;
    private final PartyroomAccessCommandService partyroomAccessCommandService;
    private final PlaybackControlPort playbackControlPort;
    private final Clock clock;

    private static final int TOTAL_MEMBERS = 400;
    private static final int SPECIAL_MEMBERS = 13;  // 1 main stage DJ + 12 general room hosts
    private static final int MAIN_STAGE_CREW = 40;
    private static final int GENERAL_ROOM_CREW = 30;
    private static final int GENERAL_ROOMS_COUNT = 12;

    @Transactional
    public DemoEnvironmentResult initializeDemoEnvironment(InitializeDemoCommand command) {
        long startTime = clock.millis();

        log.info("Starting demo environment initialization...");

        // Step 0: Find existing main stage
        log.info("Step 0: Finding existing main stage...");
        PartyroomData mainStage = findMainStage();

        // Get available avatar resources
        List<AvatarBodyResourceData> avatarBodies = adminAvatarResourcePort.findAllAvatarBodyResources();
        List<AvatarFaceResourceData> avatarFaces = adminAvatarResourcePort.findAllAvatarFaceResources();

        if (avatarBodies.isEmpty() || avatarFaces.isEmpty()) {
            throw ExceptionCreator.create(AdminException.AVATAR_RESOURCES_NOT_INITIALIZED);
        }

        // Step 1: Create virtual members
        log.info("Step 1: Creating {} virtual members...", TOTAL_MEMBERS);
        List<MemberData> specialMembers = new ArrayList<>();
        List<MemberData> regularMembers = new ArrayList<>();

        createVirtualMembers(specialMembers, regularMembers, avatarBodies, avatarFaces);

        // Step 2: Create general partyrooms only (main stage already exists)
        log.info("Step 2: Creating {} general partyrooms...", GENERAL_ROOMS_COUNT);
        List<PartyroomData> generalRooms = createGeneralRooms(command, specialMembers);

        // Step 3: Enter members into rooms
        log.info("Step 3: Entering members into rooms...");
        enterMembersIntoRooms(mainStage, generalRooms, specialMembers, regularMembers);

        // Step 4: Register DJs (optional)
        int djsRegistered = 0;
        if (command.registerDjs()) {
            log.info("Step 4: Registering DJs in queues...");
            djsRegistered = registerDjsInQueues(mainStage, generalRooms, specialMembers);
        }

        long executionTime = clock.millis() - startTime;

        log.info("Demo environment initialized successfully in {}ms", executionTime);
        log.info("Created: {} members, {} general partyrooms, {} DJs",
                TOTAL_MEMBERS, GENERAL_ROOMS_COUNT, djsRegistered);

        return buildResponse(mainStage, generalRooms, specialMembers, djsRegistered, executionTime);
    }

    private void createVirtualMembers(
            List<MemberData> specialMembers,
            List<MemberData> regularMembers,
            List<AvatarBodyResourceData> avatarBodies,
            List<AvatarFaceResourceData> avatarFaces) {

        for (int i = 0; i < TOTAL_MEMBERS; i++) {
            String nickname = NicknameGenerator.generateUnique(i + 1);

            AvatarBodyResourceData randomBody = avatarBodies.get(ThreadLocalRandom.current().nextInt(avatarBodies.size()));
            AvatarBodyUri avatarBody = new AvatarBodyUri(randomBody.getResourceUri());

            AvatarFaceUri avatarFace;
            if (randomBody.isCombinable()) {
                avatarFace = generateRandomNftFaceUri();
            } else {
                avatarFace = new AvatarFaceUri();
            }

            MemberData member = adminUserService.createVirtualMember(nickname, avatarBody, avatarFace);

            if (i < SPECIAL_MEMBERS) {
                createPlaylistAndTrack(member.getUserId());
                specialMembers.add(member);
                log.debug("Created special member {}/{}: {} (combinable: {})",
                        i + 1, SPECIAL_MEMBERS, nickname, randomBody.isCombinable());
            } else {
                regularMembers.add(member);
            }

            if ((i + 1) % 50 == 0) {
                log.info("Created {}/{} members...", i + 1, TOTAL_MEMBERS);
            }
        }

        log.info("Created {} special members and {} regular members",
                specialMembers.size(), regularMembers.size());
    }

    private PartyroomData findMainStage() {
        PartyroomData mainStage = adminPartyroomPort.findAllPartyrooms().stream()
                .filter(p -> p.getStageType() == StageType.MAIN)
                .findFirst()
                .orElseThrow(() -> ExceptionCreator.create(AdminException.MAIN_STAGE_NOT_FOUND));

        log.info("Found existing main stage: partyroomId={}", mainStage.getId());
        return mainStage;
    }

    private void createPlaylistAndTrack(UserId userId) {
        PlaylistData playlist = PlaylistData.create(1, "DJ Playlist", PlaylistType.PLAYLIST, userId);
        PlaylistData savedPlaylist = adminPlaylistPort.savePlaylist(playlist);

        DemoTrackConstants.TrackInfo track = DemoTrackConstants.getRandomTrack();
        TrackData trackData = TrackData.builder()
                .playlistId(new PlaylistId(savedPlaylist.getId()))
                .name(track.getName())
                .linkId(track.getLinkId())
                .duration(Duration.fromString(track.getDuration()))
                .thumbnailImage(track.getThumbnailImage())
                .orderNumber(1)
                .build();

        adminPlaylistPort.saveTrack(trackData);
    }

    private List<PartyroomData> createGeneralRooms(
            InitializeDemoCommand command,
            List<MemberData> specialMembers) {

        List<PartyroomData> rooms = new ArrayList<>();

        for (int i = 0; i < GENERAL_ROOMS_COUNT; i++) {
            UserId hostUserId = specialMembers.get(i + 1).getUserId();
            String title = String.format("%s %d", command.titlePrefix(), i + 1);
            String linkDomain = String.format("demo-room-%d", i + 1);

            PartyroomData partyroom = PartyroomData.create(
                    title, command.introduction(),
                    LinkDomain.of(linkDomain),
                    PlaybackTimeLimit.ofMinutes(command.playbackTimeLimit()),
                    StageType.GENERAL, hostUserId);
            PartyroomData savedPartyroom = adminPartyroomPort.savePartyroom(partyroom);
            adminPartyroomPort.savePlaybackState(PartyroomPlaybackData.createFor(savedPartyroom.getPartyroomId()));
            adminPartyroomPort.saveDjQueue(DjQueueData.createFor(savedPartyroom.getPartyroomId()));

            // Enter host
            partyroomAccessCommandService.enterByHost(hostUserId, savedPartyroom);

            rooms.add(savedPartyroom);
            log.info("Created general room {}/{}: partyroomId={}, title={}, host={}",
                    i + 1, GENERAL_ROOMS_COUNT, savedPartyroom.getId(), title, hostUserId.getUid());
        }

        return rooms;
    }

    private void enterMembersIntoRooms(
            PartyroomData mainStage,
            List<PartyroomData> generalRooms,
            List<MemberData> specialMembers,
            List<MemberData> regularMembers) {

        int memberIndex = 0;

        MemberData mainStageDj = specialMembers.get(0);
        enterMemberAsRegularCrew(mainStage, mainStageDj.getUserId());

        for (int i = 0; i < MAIN_STAGE_CREW - 1 && memberIndex < regularMembers.size(); i++) {
            MemberData member = regularMembers.get(memberIndex++);
            enterMemberAsRegularCrew(mainStage, member.getUserId());
        }
        log.info("Entered {} crew into main stage (including 1 special member as DJ)", MAIN_STAGE_CREW);

        for (int roomIdx = 0; roomIdx < generalRooms.size(); roomIdx++) {
            PartyroomData room = generalRooms.get(roomIdx);

            for (int i = 0; i < GENERAL_ROOM_CREW - 1 && memberIndex < regularMembers.size(); i++) {
                MemberData member = regularMembers.get(memberIndex++);
                enterMemberAsRegularCrew(room, member.getUserId());
            }
            log.info("Entered {} crew into room {}/{}", GENERAL_ROOM_CREW, roomIdx + 1, GENERAL_ROOMS_COUNT);
        }

        log.info("Total members entered: {} (used {} regular members)",
                MAIN_STAGE_CREW + (GENERAL_ROOM_CREW * GENERAL_ROOMS_COUNT), memberIndex);
    }

    private void enterMemberAsRegularCrew(PartyroomData partyroom, UserId userId) {
        PartyroomData loadedPartyroom = adminPartyroomPort.findPartyroomById(partyroom.getPartyroomId().getId())
                .orElseThrow();

        CrewData crew = CrewData.create(loadedPartyroom.getPartyroomId(), userId, GradeType.LISTENER, null, LocalDateTime.now(clock));
        adminPartyroomPort.saveCrew(crew);
    }

    private int registerDjsInQueues(
            PartyroomData mainStage,
            List<PartyroomData> generalRooms,
            List<MemberData> specialMembers) {

        int djCount = 0;

        registerDjInRoom(mainStage, specialMembers.get(0).getUserId());
        djCount++;

        for (int i = 0; i < generalRooms.size(); i++) {
            PartyroomData room = generalRooms.get(i);
            UserId hostUserId = specialMembers.get(i + 1).getUserId();
            registerDjInRoom(room, hostUserId);
            djCount++;
        }

        log.info("Registered {} DJs in queues", djCount);
        return djCount;
    }

    private void registerDjInRoom(PartyroomData partyroom, UserId userId) {
        List<PlaylistData> playlists = adminPlaylistPort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST);
        Optional<PlaylistData> playlistOpt = playlists.stream().findFirst();

        if (playlistOpt.isEmpty()) {
            log.warn("No playlist found for user {}, skipping DJ registration", userId.getUid());
            return;
        }

        PlaylistData playlist = playlistOpt.get();

        PartyroomData loadedPartyroom = adminPartyroomPort.findPartyroomById(partyroom.getPartyroomId().getId())
                .orElseThrow();
        PartyroomPlaybackData playbackState = adminPartyroomPort.findPlaybackState(loadedPartyroom.getPartyroomId()).orElseThrow();

        boolean isPostActivationProcessingRequired = !playbackState.isActivated();

        // Find crew
        CrewData crew = adminPartyroomPort.findCrewByPartyroomAndUser(loadedPartyroom.getPartyroomId(), userId)
                .orElseThrow();
        CrewId crewId = new CrewId(crew.getId());

        // Check if already registered
        if (adminPartyroomPort.existsDjByPartyroomAndCrew(loadedPartyroom.getPartyroomId(), crewId)) {
            log.warn("DJ already registered for user {}, skipping", userId.getUid());
            return;
        }

        // Calculate next order number
        List<DjData> queuedDjs = adminPartyroomPort.findDjsByPartyroomOrderByOrder(loadedPartyroom.getPartyroomId());
        int nextOrder = queuedDjs.size() + 1;

        // Create and save DJ
        DjData dj = DjData.create(loadedPartyroom.getPartyroomId(), new PlaylistId(playlist.getId()), crewId, nextOrder);
        adminPartyroomPort.saveDj(dj);

        playbackState.activate(null, null);
        adminPartyroomPort.savePlaybackState(playbackState);

        // Start playback if this is the first DJ
        if (isPostActivationProcessingRequired) {
            playbackControlPort.startPlayback(loadedPartyroom);
            log.info("Started playback for partyroom: partyroomId={}, djUserId={}",
                    partyroom.getPartyroomId().getId(), userId.getUid());
        }

        log.debug("Registered DJ: userId={}, playlistId={}, partyroomId={}",
                userId.getUid(), playlist.getId(), partyroom.getPartyroomId().getId());
    }

    private DemoEnvironmentResult buildResponse(
            PartyroomData mainStage,
            List<PartyroomData> generalRooms,
            List<MemberData> specialMembers,
            int djsRegistered,
            long executionTime) {

        UserId mainStageDjUserId = specialMembers.get(0).getUserId();
        Long mainStagePlaylistId = findPlaylistId(mainStageDjUserId);

        DemoEnvironmentResult.PartyroomDetail mainStageDetail = new DemoEnvironmentResult.PartyroomDetail(
                mainStage.getPartyroomId().getId(),
                mainStage.getStageType().name(),
                mainStage.getTitle(),
                mainStage.getLinkDomain().getValue(),
                null,
                MAIN_STAGE_CREW,
                mainStageDjUserId.getUid().toString(),
                mainStagePlaylistId
        );

        List<DemoEnvironmentResult.PartyroomDetail> generalRoomDetails = new ArrayList<>();
        for (int i = 0; i < generalRooms.size(); i++) {
            PartyroomData room = generalRooms.get(i);
            UserId hostUserId = specialMembers.get(i + 1).getUserId();
            Long playlistId = findPlaylistId(hostUserId);

            DemoEnvironmentResult.PartyroomDetail detail = new DemoEnvironmentResult.PartyroomDetail(
                    room.getPartyroomId().getId(),
                    room.getStageType().name(),
                    room.getTitle(),
                    room.getLinkDomain().getValue(),
                    hostUserId.getUid().toString(),
                    GENERAL_ROOM_CREW,
                    hostUserId.getUid().toString(),
                    playlistId
            );

            generalRoomDetails.add(detail);
        }

        return new DemoEnvironmentResult(
                TOTAL_MEMBERS,
                SPECIAL_MEMBERS,
                1 + GENERAL_ROOMS_COUNT,
                djsRegistered,
                executionTime,
                mainStageDetail,
                generalRoomDetails
        );
    }

    private Long findPlaylistId(UserId userId) {
        List<PlaylistData> playlists = adminPlaylistPort.findPlaylistsByOwnerAndType(userId, PlaylistType.PLAYLIST);
        return playlists.stream().findFirst().map(PlaylistData::getId).orElse(null);
    }

    private AvatarFaceUri generateRandomNftFaceUri() {
        int faceNumber = ThreadLocalRandom.current().nextInt(30) + 1;
        String faceFileName = String.format("ava_nft_tmp_%03d.png", faceNumber);
        String faceUri = "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/" +
                "ava_nft_tmp%2F" + faceFileName + "?alt=media";
        return new AvatarFaceUri(faceUri);
    }

    @Transactional(readOnly = true)
    public DemoStatusResult getDemoEnvironmentStatus() {
        long virtualMemberCount = adminMemberPort.countMembersByProviderType(ProviderType.ADMIN);
        long generalRoomCount = adminPartyroomPort.findAllPartyrooms().stream()
                .filter(p -> !p.isTerminated() && p.getStageType() == StageType.GENERAL)
                .count();

        boolean initialized = virtualMemberCount > 0;

        return new DemoStatusResult(initialized, virtualMemberCount, generalRoomCount);
    }

    @Transactional(readOnly = true)
    public AdminPartyroomListResult getPartyrooms() {
        List<AdminPartyroomListResult.PartyroomItem> items = adminPartyroomPort.findAllPartyrooms().stream()
                .filter(p -> !p.isTerminated())
                .map(p -> {
                    int crewCount = (int) adminPartyroomPort.countActiveCrewByPartyroom(p.getPartyroomId());
                    int djCount = adminPartyroomPort.findDjsByPartyroomOrderByOrder(p.getPartyroomId()).size();
                    boolean isPlaybackActivated = adminPartyroomPort.findPlaybackState(p.getPartyroomId())
                            .map(PartyroomPlaybackData::isActivated).orElse(false);
                    return new AdminPartyroomListResult.PartyroomItem(
                            p.getId(),
                            p.getStageType().name(),
                            p.getTitle(),
                            p.getLinkDomain().getValue(),
                            crewCount,
                            djCount,
                            isPlaybackActivated
                    );
                })
                .toList();

        return new AdminPartyroomListResult(items);
    }
}
