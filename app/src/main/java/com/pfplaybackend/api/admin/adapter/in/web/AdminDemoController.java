package com.pfplaybackend.api.admin.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.payload.request.InitializeDemoEnvironmentRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.request.SimulateReactionsRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.request.StartChatSimulationRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.ChatSimulationStartResponse;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.ChatSimulationStopResponse;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.InitializeDemoEnvironmentResponse;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.QueryAdminPartyroomListResponse;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.QueryDemoEnvironmentStatusResponse;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.SimulateReactionsResponse;
import com.pfplaybackend.api.admin.application.dto.command.InitializeDemoCommand;
import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomListResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoEnvironmentResult;
import com.pfplaybackend.api.admin.application.dto.result.DemoStatusResult;
import com.pfplaybackend.api.admin.application.dto.result.SimulateReactionsResult;
import com.pfplaybackend.api.admin.application.service.AdminDemoService;
import com.pfplaybackend.api.admin.application.service.AdminPartyroomService;
import com.pfplaybackend.api.admin.application.service.ChatSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@Slf4j
@io.swagger.v3.oas.annotations.Hidden
@Tag(name = "Admin Demo API", description = "Demo environment initialization and simulation")
@RestController
@RequestMapping("/api/v1/admin/demo")
@RequiredArgsConstructor
public class AdminDemoController {

    private final AdminDemoService adminDemoService;
    private final AdminPartyroomService adminPartyroomService;
    private final ChatSimulationService chatSimulationService;

    @Operation(summary = "데모 환경 상태 확인", description = "현재 데모 환경의 초기화 여부, 가상 멤버 수, 파티룸 수를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "데모 환경 상태 조회 성공",
                    content = @Content(schema = @Schema(implementation = QueryDemoEnvironmentStatusResponse.class)))
    })
    @GetMapping("/status")
    public ResponseEntity<QueryDemoEnvironmentStatusResponse> getDemoEnvironmentStatus() {
        DemoStatusResult result = adminDemoService.getDemoEnvironmentStatus();
        return ResponseEntity.ok(QueryDemoEnvironmentStatusResponse.builder()
                .initialized(result.initialized())
                .virtualMemberCount(result.virtualMemberCount())
                .generalRoomCount(result.generalRoomCount())
                .build());
    }

    @Operation(summary = "활성 파티룸 전체 조회", description = "현재 활성 상태인 모든 파티룸 목록을 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "파티룸 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = QueryAdminPartyroomListResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/partyrooms")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<QueryAdminPartyroomListResponse> getPartyrooms() {
        AdminPartyroomListResult result = adminDemoService.getPartyrooms();
        QueryAdminPartyroomListResponse response = QueryAdminPartyroomListResponse.builder()
                .partyrooms(result.partyrooms().stream()
                        .map(item -> QueryAdminPartyroomListResponse.PartyroomItem.builder()
                                .partyroomId(item.partyroomId())
                                .stageType(item.stageType())
                                .title(item.title())
                                .linkDomain(item.linkDomain())
                                .crewCount(item.crewCount())
                                .djCount(item.djCount())
                                .playbackActivated(item.playbackActivated())
                                .build())
                        .collect(Collectors.toList()))
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "데모 환경 전체 초기화", description = "가상 멤버 생성, 파티룸 생성, DJ 등록 등 데모에 필요한 전체 환경을 한 번에 초기화합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "데모 환경 초기화 성공",
                    content = @Content(schema = @Schema(implementation = InitializeDemoEnvironmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/init")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<InitializeDemoEnvironmentResponse> initializeDemoEnvironment(
            @Valid @RequestBody InitializeDemoEnvironmentRequest request) {

        log.warn("!!! INITIALIZING FULL DEMO ENVIRONMENT !!!");

        InitializeDemoCommand command = new InitializeDemoCommand(
                request.getPlaybackTimeLimit(), request.getTitlePrefix(),
                request.getIntroduction(), request.getRegisterDjs());

        DemoEnvironmentResult result = adminDemoService.initializeDemoEnvironment(command);

        log.info("Demo environment initialized: {} members, {} partyrooms, {} DJs, {}ms",
                result.totalMembers(), result.totalPartyrooms(),
                result.totalDjsRegistered(), result.executionTimeMs());

        InitializeDemoEnvironmentResponse response = InitializeDemoEnvironmentResponse.builder()
                .totalMembers(result.totalMembers())
                .specialMembers(result.specialMembers())
                .totalPartyrooms(result.totalPartyrooms())
                .totalDjsRegistered(result.totalDjsRegistered())
                .executionTimeMs(result.executionTimeMs())
                .mainStage(toPartyroomDetail(result.mainStage()))
                .generalRooms(result.generalRooms().stream()
                        .map(this::toPartyroomDetail)
                        .collect(Collectors.toList()))
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "파티룸 리액션 시뮬레이션", description = "지정된 파티룸에서 가상 멤버들의 좋아요/싫어요/그랩 리액션을 시뮬레이션합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "리액션 시뮬레이션 성공",
                    content = @Content(schema = @Schema(implementation = SimulateReactionsResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "해당 파티룸을 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/partyrooms/{partyroomId}/reactions")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<SimulateReactionsResponse> simulateReactions(
            @Parameter(description = "리액션을 시뮬레이션할 파티룸 ID") @PathVariable Long partyroomId,
            @Valid @RequestBody SimulateReactionsRequest request) {

        log.info("Admin simulating reactions: partyroomId={}", partyroomId);
        SimulateReactionsResult result = adminPartyroomService.simulateReactions(partyroomId);

        SimulateReactionsResponse response = SimulateReactionsResponse.builder()
                .partyroomId(result.partyroomId())
                .playbackId(result.playbackId())
                .reactions(result.reactions().stream()
                        .map(r -> SimulateReactionsResponse.SimulatedReaction.builder()
                                .userId(r.userId())
                                .reactionType(r.reactionType())
                                .eventPublished(r.eventPublished())
                                .build())
                        .collect(Collectors.toList()))
                .aggregation(SimulateReactionsResponse.AggregationCounts.builder()
                        .likeCount(result.aggregation().likeCount())
                        .dislikeCount(result.aggregation().dislikeCount())
                        .grabCount(result.aggregation().grabCount())
                        .build())
                .build();

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "채팅 시뮬레이션 시작", description = "지정된 파티룸에서 가상 멤버들의 채팅 시뮬레이션을 시작합니다. 스크립트 타입에 따라 다른 대화 패턴이 적용됩니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "채팅 시뮬레이션 시작 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 스크립트 타입"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "해당 파티룸을 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/partyrooms/{partyroomId}/chat")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ChatSimulationStartResponse> startChatSimulation(
            @Parameter(description = "채팅 시뮬레이션을 시작할 파티룸 ID") @PathVariable Long partyroomId,
            @Valid @RequestBody StartChatSimulationRequest request) {

        log.info("Admin starting chat simulation: partyroomId={}, scriptType={}",
                partyroomId, request.getScriptType());

        chatSimulationService.startChatSimulation(partyroomId, request.getScriptType());

        return ResponseEntity.ok(new ChatSimulationStartResponse(partyroomId, "STARTED", request.getScriptType()));
    }

    @Operation(summary = "채팅 시뮬레이션 중지", description = "지정된 파티룸에서 실행 중인 채팅 시뮬레이션을 중지합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "채팅 시뮬레이션 중지 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "해당 파티룸을 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @DeleteMapping("/partyrooms/{partyroomId}/chat")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ChatSimulationStopResponse> stopChatSimulation(
            @Parameter(description = "채팅 시뮬레이션을 중지할 파티룸 ID") @PathVariable Long partyroomId) {

        log.info("Admin stopping chat simulation: partyroomId={}", partyroomId);
        chatSimulationService.stopChatSimulation(partyroomId);

        return ResponseEntity.ok(new ChatSimulationStopResponse(partyroomId, "STOPPED"));
    }

    private InitializeDemoEnvironmentResponse.PartyroomDetail toPartyroomDetail(DemoEnvironmentResult.PartyroomDetail detail) {
        return InitializeDemoEnvironmentResponse.PartyroomDetail.builder()
                .partyroomId(detail.partyroomId())
                .stageType(detail.stageType())
                .title(detail.title())
                .linkDomain(detail.linkDomain())
                .hostUserId(detail.hostUserId())
                .totalCrewCount(detail.totalCrewCount())
                .djUserId(detail.djUserId())
                .playlistId(detail.playlistId())
                .build();
    }
}
