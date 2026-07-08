package com.pfplaybackend.api.virtualdj.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.playlist.adapter.in.web.payload.response.QueryMusicSearchResponse;
import com.pfplaybackend.api.playlist.application.service.search.MusicSearchService;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.AddPackTrackRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.ApplyVirtualDjConfigRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.AssignPersonaRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.AssignPersonaResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.AvatarCatalogItemResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.BotRosterItemResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.BulkApplyVirtualDjConfigRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.ChatConfigResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.CreatePersonaRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.CreateSongPackRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.CreatedIdResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.UpdatePersonaRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.DistributeBotAvatarRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.DistributeBotAvatarResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.PoolSummaryResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.ProvisionPoolRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.RenameSongPackRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.SetBotAvatarRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.SongPackDetailResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.SongPackListItemResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.UnassignPersonaRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.UpdateChatConfigRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.VirtualDjLiveStatusResponse;
import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAdminService;
import com.pfplaybackend.api.virtualdj.application.service.BotPersonaAssignmentService;
import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAssigner;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjAdminService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjChatConfigAdminService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualPersonaService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualSongPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 가상 DJ(P2) API — 봇 풀 / 송 팩 CRUD / 룸별 config / 일괄 / live status.
 *
 * <p>feature-cohesive 배치: administration BC 가 아니라 {@code virtualdj} feature 의 inbound adapter 에 둔다
 * (송 팩·봇 풀·reconcile 이 모두 이 feature 응집). 공통 {@code @adminAuth.canManageVirtualDj()} SpEL 로 게이팅하고
 * {@code @SecurityRequirement(name="cookieAuth")} + {@link ApiCommonResponse} 봉투를 쓴다. 도메인 예외
 * (CONFIG_NOT_FOUND 404, SONG_PACK_* 409/404, INVALID_CONFIG 400)는 GlobalExceptionHandler 가 매핑한다.
 */
@Tag(name = "Admin Virtual DJ API", description = "P2 가상 DJ 어드민 운영 (봇 풀/송 팩/룸 config/일괄)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminVirtualDjController {

    private final VirtualDjAdminService adminService;
    private final VirtualSongPackService songPackService;
    private final VirtualPersonaService personaService;
    private final MusicSearchService musicSearchService;
    private final BotAvatarAdminService botAvatarAdminService;
    private final BotPersonaAssignmentService botPersonaAssignmentService;
    private final VirtualDjChatConfigAdminService chatConfigAdminService;

    // ── 음악 검색 (어드민 프록시) ──

    @Operation(summary = "어드민 음악 검색 (송팩 빌더용)",
            description = "회원전용 /music-search 와 동일 서비스, 어드민 인증 경로")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/music-search")
    public ResponseEntity<ApiCommonResponse<QueryMusicSearchResponse>> searchMusic(@RequestParam("q") String q) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                QueryMusicSearchResponse.from(musicSearchService.getSearchList(q))));
    }

    // ── 봇 풀 ──

    @Operation(summary = "봇 풀 요약 조회")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/pool")
    public ResponseEntity<ApiCommonResponse<PoolSummaryResponse>> poolSummary() {
        return ResponseEntity.ok(ApiCommonResponse.success(PoolSummaryResponse.from(adminService.poolSummary())));
    }

    @Operation(summary = "봇 풀 프로비저닝")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/pool")
    public ResponseEntity<ApiCommonResponse<Void>> provisionPool(
            @Valid @RequestBody ProvisionPoolRequest req) {
        adminService.provisionPool(req.count());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCommonResponse.ok());
    }

    // ── 송 팩 CRUD ──

    @Operation(summary = "송 팩 목록 조회", description = "전체 송 팩 목록 (트랙 수 포함)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/song-packs")
    public ResponseEntity<ApiCommonResponse<List<SongPackListItemResponse>>> listSongPacks() {
        List<SongPackListItemResponse> items = songPackService.listPacks().stream()
                .map(SongPackListItemResponse::from)
                .toList();
        return ResponseEntity.ok(ApiCommonResponse.success(items));
    }

    @Operation(summary = "송 팩 상세 조회", description = "트랙 목록(orderNumber 오름차순) 포함")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/song-packs/{id}")
    public ResponseEntity<ApiCommonResponse<SongPackDetailResponse>> getSongPack(
            @PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                SongPackDetailResponse.from(songPackService.getPack(id))));
    }

    @Operation(summary = "송 팩 생성")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/song-packs")
    public ResponseEntity<ApiCommonResponse<CreatedIdResponse>> createSongPack(
            @Valid @RequestBody CreateSongPackRequest req) {
        Long id = songPackService.createPack(req.name(), req.description());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new CreatedIdResponse(id)));
    }

    @Operation(summary = "송 팩 이름 변경")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/virtual-dj/song-packs/{id}")
    public ResponseEntity<Void> renameSongPack(@PathVariable("id") Long id,
                                               @Valid @RequestBody RenameSongPackRequest req) {
        songPackService.renamePack(id, req.name());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "송 팩 삭제")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @DeleteMapping("/virtual-dj/song-packs/{id}")
    public ResponseEntity<Void> deleteSongPack(@PathVariable("id") Long id) {
        songPackService.deletePack(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "송 팩 트랙 추가", description = "어드민 프론트의 music-search 로 선택된 트랙을 그대로 수신")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/song-packs/{id}/tracks")
    public ResponseEntity<ApiCommonResponse<CreatedIdResponse>> addSongPackTrack(
            @PathVariable("id") Long id, @Valid @RequestBody AddPackTrackRequest req) {
        Long trackId = songPackService.addTrack(id, req.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new CreatedIdResponse(trackId)));
    }

    @Operation(summary = "송 팩 트랙 삭제")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @DeleteMapping("/virtual-dj/song-packs/{id}/tracks/{trackId}")
    public ResponseEntity<Void> removeSongPackTrack(@PathVariable("id") Long id,
                                                    @PathVariable("trackId") Long trackId) {
        songPackService.removeTrack(id, trackId);
        return ResponseEntity.noContent().build();
    }

    // ── 페르소나 CRUD ──

    @Operation(summary = "페르소나 목록 조회", description = "전체 페르소나 목록 (활성 여부 포함)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/personas")
    public ResponseEntity<ApiCommonResponse<List<VirtualPersonaService.PersonaListItem>>> listPersonas() {
        return ResponseEntity.ok(ApiCommonResponse.success(personaService.list()));
    }

    @Operation(summary = "페르소나 상세 조회", description = "instruction 포함")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/personas/{id}")
    public ResponseEntity<ApiCommonResponse<VirtualPersonaService.PersonaDetail>> getPersona(
            @PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiCommonResponse.success(personaService.get(id)));
    }

    @Operation(summary = "페르소나 생성")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/personas")
    public ResponseEntity<ApiCommonResponse<CreatedIdResponse>> createPersona(
            @Valid @RequestBody CreatePersonaRequest req) {
        Long id = personaService.create(req.name(), req.instruction());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new CreatedIdResponse(id)));
    }

    @Operation(summary = "페르소나 수정", description = "이름·instruction·활성 상태 일괄 변경")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/virtual-dj/personas/{id}")
    public ResponseEntity<Void> updatePersona(@PathVariable("id") Long id,
                                              @Valid @RequestBody UpdatePersonaRequest req) {
        personaService.update(id, req.name(), req.instruction(), req.active());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "페르소나 삭제")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @DeleteMapping("/virtual-dj/personas/{id}")
    public ResponseEntity<Void> deletePersona(@PathVariable("id") Long id) {
        personaService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── 룸별 config ──

    @Operation(summary = "룸 가상 DJ config 적용", description = "MANAGED 적용 후 reconcile 즉시 트리거, OFF 면 drain")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/partyrooms/{partyroomId}/virtual-dj")
    public ResponseEntity<Void> applyConfig(@PathVariable("partyroomId") Long partyroomId,
                                            @Valid @RequestBody ApplyVirtualDjConfigRequest req) {
        adminService.applyConfig(new PartyroomId(partyroomId), req.status(),
                req.targetCount(), req.djBotCount(), req.songPackId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "룸 비우기(drain)", description = "config OFF + 모든 봇 즉시 제거(anti-flap dwell 무시, exit 경로)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/partyrooms/{partyroomId}/virtual-dj/drain")
    public ResponseEntity<Void> drain(@PathVariable("partyroomId") Long partyroomId) {
        adminService.drain(new PartyroomId(partyroomId));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "룸 가상 DJ live 상태 조회")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/partyrooms/{partyroomId}/virtual-dj")
    public ResponseEntity<ApiCommonResponse<VirtualDjLiveStatusResponse>> liveStatus(
            @PathVariable("partyroomId") Long partyroomId) {
        VirtualDjAdminService.LiveStatus s = adminService.liveStatus(new PartyroomId(partyroomId));
        return ResponseEntity.ok(ApiCommonResponse.success(VirtualDjLiveStatusResponse.from(s)));
    }

    // ── 일괄 ──

    @Operation(summary = "여러 룸 config 일괄 적용", description = "체크박스 일괄 — MANAGED 는 각각 reconcile")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/virtual-dj/bulk")
    public ResponseEntity<Void> applyBulk(@Valid @RequestBody BulkApplyVirtualDjConfigRequest req) {
        adminService.applyBulk(req.partyroomIds(), req.status(),
                req.targetCount(), req.djBotCount(), req.songPackId());
        return ResponseEntity.noContent().build();
    }

    // ── 봇 아바타 (P1) ──

    @Operation(summary = "아바타 카탈로그 조회 (피커용)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/avatar-catalog")
    public ResponseEntity<ApiCommonResponse<List<AvatarCatalogItemResponse>>> avatarCatalog() {
        List<AvatarCatalogItemResponse> items = botAvatarAdminService.catalog().stream()
                .map(AvatarCatalogItemResponse::from).toList();
        return ResponseEntity.ok(ApiCommonResponse.success(items));
    }

    @Operation(summary = "봇 로스터 조회 (신원+현재 아바타+배치룸)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/bots")
    public ResponseEntity<ApiCommonResponse<List<BotRosterItemResponse>>> bots() {
        List<BotRosterItemResponse> items = botAvatarAdminService.roster().stream()
                .map(BotRosterItemResponse::from).toList();
        return ResponseEntity.ok(ApiCommonResponse.success(items));
    }

    @Operation(summary = "봇 개별 아바타 설정")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/virtual-dj/bots/{userId}/avatar")
    public ResponseEntity<Void> setBotAvatar(@PathVariable("userId") Long userId,
                                             @Valid @RequestBody SetBotAvatarRequest req) {
        botAvatarAdminService.setIndividual(new UserId(userId), req.avatarBodyUri());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "봇 아바타 일괄 변별 배분", description = "선택 봇들에 셋에서 랜덤 1개씩 배분")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/bots/avatar/distribute")
    public ResponseEntity<ApiCommonResponse<DistributeBotAvatarResponse>> distributeBotAvatars(
            @Valid @RequestBody DistributeBotAvatarRequest req) {
        List<BotAvatarAssigner.Assigned> assigned = botAvatarAdminService.distribute(req.botIds(), req.bodyUris());
        return ResponseEntity.ok(ApiCommonResponse.success(DistributeBotAvatarResponse.from(assigned)));
    }

    // ── 봇↔페르소나 매핑 (P3) ──

    @Operation(summary = "봇 페르소나 일괄 매핑", description = "선택 봇들에 페르소나 1개 일괄 매핑(이미 매핑된 봇은 교체)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/bots/persona/assign")
    public ResponseEntity<ApiCommonResponse<AssignPersonaResponse>> assignPersona(
            @Valid @RequestBody AssignPersonaRequest req) {
        int applied = botPersonaAssignmentService.assign(req.botIds(), req.personaId());
        return ResponseEntity.ok(ApiCommonResponse.success(new AssignPersonaResponse(applied)));
    }

    @Operation(summary = "봇 페르소나 일괄 해제", description = "선택 봇들의 페르소나 매핑 해제")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/bots/persona/unassign")
    public ResponseEntity<ApiCommonResponse<AssignPersonaResponse>> unassignPersona(
            @Valid @RequestBody UnassignPersonaRequest req) {
        int applied = botPersonaAssignmentService.unassign(req.botIds());
        return ResponseEntity.ok(ApiCommonResponse.success(new AssignPersonaResponse(applied)));
    }

    // ── 채팅/자가갱신 설정 (P3) ──

    @Operation(summary = "가상 DJ 채팅/자가갱신 설정 조회",
            description = "vdj.chat.* 5키 + 자가갱신 forward-gate 1키 (system_config, fail-open 폴백)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/chat-config")
    public ResponseEntity<ApiCommonResponse<ChatConfigResponse>> getChatConfig() {
        return ResponseEntity.ok(ApiCommonResponse.success(
                ChatConfigResponse.from(chatConfigAdminService.read())));
    }

    @Operation(summary = "가상 DJ 채팅/자가갱신 설정 변경",
            description = "6키 일괄 upsert 후 SystemConfigCache 무효화(AFTER_COMMIT)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/virtual-dj/chat-config")
    public ResponseEntity<Void> updateChatConfig(@Valid @RequestBody UpdateChatConfigRequest req) {
        chatConfigAdminService.update(req.chatEnabled(), req.selfUpdateEnabled(),
                req.probabilityPercent(), req.cooldownSeconds(), req.contextSize(), req.outputMaxTokens());
        return ResponseEntity.noContent().build();
    }
}
