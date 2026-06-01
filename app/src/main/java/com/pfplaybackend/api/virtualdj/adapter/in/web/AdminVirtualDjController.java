package com.pfplaybackend.api.virtualdj.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.AddPackTrackRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.ApplyVirtualDjConfigRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.BulkApplyVirtualDjConfigRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.CreateSongPackRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.CreatedIdResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.PoolSummaryResponse;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.ProvisionPoolRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.RenameSongPackRequest;
import com.pfplaybackend.api.virtualdj.adapter.in.web.payload.VirtualDjLiveStatusResponse;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjAdminService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualSongPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    // ── 봇 풀 ──

    @Operation(summary = "봇 풀 요약 조회")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/pool")
    public ResponseEntity<ApiCommonResponse<PoolSummaryResponse>> poolSummary() {
        return ResponseEntity.ok(ApiCommonResponse.success(adminService.poolSummary()));
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

    // ── 룸별 config ──

    @Operation(summary = "룸 가상 DJ config 적용", description = "MANAGED 적용 후 reconcile 즉시 트리거, OFF 면 drain")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/partyrooms/{partyroomId}/virtual-dj")
    public ResponseEntity<Void> applyConfig(@PathVariable("partyroomId") Long partyroomId,
                                            @Valid @RequestBody ApplyVirtualDjConfigRequest req) {
        adminService.applyConfig(new PartyroomId(partyroomId), req.status(),
                req.targetCount(), req.companionFloor(), req.songPackId());
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

    @Operation(summary = "룸 가상 DJ 동결(freeze)", description = "현재 봇 수 유지, 신규 조정 금지")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/partyrooms/{partyroomId}/virtual-dj/freeze")
    public ResponseEntity<Void> freeze(@PathVariable("partyroomId") Long partyroomId) {
        adminService.freeze(new PartyroomId(partyroomId));
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
                req.targetCount(), req.companionFloor(), req.songPackId());
        return ResponseEntity.noContent().build();
    }
}
