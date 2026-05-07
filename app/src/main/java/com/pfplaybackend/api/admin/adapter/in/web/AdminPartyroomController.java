package com.pfplaybackend.api.admin.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.payload.request.AdminCreatePartyroomRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.request.CreateBulkPreviewEnvironmentRequest;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.CreateAdminPartyroomResponse;
import com.pfplaybackend.api.admin.adapter.in.web.payload.response.CreateBulkPreviewEnvironmentResponse;
import com.pfplaybackend.api.admin.application.dto.command.AdminCreatePartyroomCommand;
import com.pfplaybackend.api.admin.application.dto.command.BulkPreviewCommand;
import com.pfplaybackend.api.admin.application.dto.result.AdminPartyroomResult;
import com.pfplaybackend.api.admin.application.dto.result.BulkPreviewResult;
import com.pfplaybackend.api.admin.application.service.AdminPartyroomService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

@Slf4j
@io.swagger.v3.oas.annotations.Hidden
@Tag(name = "Admin Partyroom API", description = "Admin operations for partyroom management")
@RestController
@RequestMapping("/api/v1/admin/partyrooms")
@RequiredArgsConstructor
public class AdminPartyroomController {

    private final AdminPartyroomService adminPartyroomService;

    @Operation(summary = "호스트 지정 파티룸 생성", description = "관리자가 특정 사용자를 호스트로 지정하여 파티룸을 생성합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "파티룸 생성 성공",
                    content = @Content(schema = @Schema(implementation = CreateAdminPartyroomResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다"),
            @ApiResponse(responseCode = "404", description = "지정된 호스트 사용자를 찾을 수 없습니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping
    @PreAuthorize("@adminAuth.canSuspendPartyroom()")
    public ResponseEntity<CreateAdminPartyroomResponse> createPartyroom(
            @Valid @RequestBody AdminCreatePartyroomRequest request) {

        log.info("Admin creating partyroom: hostUserId={}, title={}",
                request.getHostUserId(), request.getTitle());

        AdminCreatePartyroomCommand command = new AdminCreatePartyroomCommand(
                request.getHostUserId(), request.getTitle(), request.getIntroduction(),
                request.getLinkDomain(), request.getPlaybackTimeLimit());

        AdminPartyroomResult result = adminPartyroomService.createPartyroomWithHost(command);

        CreateAdminPartyroomResponse response = CreateAdminPartyroomResponse.builder()
                .partyroomId(result.partyroomId())
                .hostUserId(result.hostUserId())
                .title(result.title())
                .introduction(result.introduction())
                .linkDomain(result.linkDomain())
                .playbackTimeLimit(result.playbackTimeLimit())
                .stageType(result.stageType())
                .isActive(result.isActive())
                .createdAt(result.createdAt())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "대량 프리뷰 환경 생성", description = "여러 파티룸을 한 번에 생성하고 각 파티룸에 가상 멤버를 배정하여 프리뷰 환경을 구성합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "대량 프리뷰 환경 생성 성공",
                    content = @Content(schema = @Schema(implementation = CreateBulkPreviewEnvironmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "FM 권한이 필요합니다")
    })
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/bulk-preview")
    @PreAuthorize("@adminAuth.canSuspendPartyroom()")
    public ResponseEntity<CreateBulkPreviewEnvironmentResponse> createBulkPreviewEnvironment(
            @Valid @RequestBody CreateBulkPreviewEnvironmentRequest request) {

        log.info("Admin creating bulk preview environment: {} partyrooms with {} users each",
                request.getPartyroomCount(), request.getUsersPerRoom());

        BulkPreviewCommand command = new BulkPreviewCommand(
                request.getPartyroomCount(), request.getUsersPerRoom(), request.getTitlePrefix(),
                request.getIntroduction(), request.getLinkDomainPrefix(), request.getPlaybackTimeLimit());

        BulkPreviewResult result = adminPartyroomService.createBulkPreviewEnvironment(command);

        CreateBulkPreviewEnvironmentResponse response = CreateBulkPreviewEnvironmentResponse.builder()
                .totalPartyrooms(result.totalPartyrooms())
                .totalVirtualMembers(result.totalVirtualMembers())
                .executionTimeMs(result.executionTimeMs())
                .partyrooms(result.partyrooms().stream()
                        .map(s -> CreateBulkPreviewEnvironmentResponse.PartyroomSummary.builder()
                                .partyroomId(s.partyroomId())
                                .title(s.title())
                                .linkDomain(s.linkDomain())
                                .hostUserId(s.hostUserId())
                                .crewCount(s.crewCount())
                                .crewUserIds(s.crewUserIds())
                                .build())
                        .collect(Collectors.toList()))
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
