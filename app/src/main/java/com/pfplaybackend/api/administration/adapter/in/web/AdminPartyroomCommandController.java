package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.SuspendPartyroomRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.TerminatePartyroomRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.UpdateDisplayFlagRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.UpdatePartyroomMetaRequest;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomCommandService;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-3~B-6 어드민 파티룸 상태/메타 변경 커맨드 controller.
 *
 * <p>모든 endpoint는 {@code @adminAuth.isAdmin()}로 게이팅되며 204 No Content를 반환한다.
 * 도메인 예외(NOT_FOUND_ROOM 404, ALREADY_TERMINATED 403, ILLEGAL_STATE_TRANSITION 409)는
 * {@link com.pfplaybackend.api.common.exception.GlobalExceptionHandler}가 매핑한다.
 */
@Tag(name = "Admin Partyroom Commands API", description = "B-3~B-6 어드민 파티룸 상태/메타 변경")
@RestController
@RequestMapping("/api/v1/admin/partyrooms")
@RequiredArgsConstructor
public class AdminPartyroomCommandController {

    private final AdminPartyroomCommandService commandService;
    private final AdminContext adminContext;

    @Operation(summary = "B-3 룸 강제 종료")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{partyroomId}/terminate")
    public ResponseEntity<Void> terminate(@PathVariable Long partyroomId,
                                          @Valid @RequestBody TerminatePartyroomRequest req) {
        commandService.terminate(new PartyroomId(partyroomId), req.reason(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-4 룸 일시 정지")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{partyroomId}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable Long partyroomId,
                                        @Valid @RequestBody SuspendPartyroomRequest req) {
        commandService.suspend(new PartyroomId(partyroomId), req.reason(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-4 룸 재개")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{partyroomId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long partyroomId) {
        commandService.restore(new PartyroomId(partyroomId), adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-5 룸 메타데이터 수정")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{partyroomId}")
    public ResponseEntity<Void> updateMeta(@PathVariable Long partyroomId,
                                           @Valid @RequestBody UpdatePartyroomMetaRequest req) {
        commandService.updateMeta(new PartyroomId(partyroomId),
                req.title(), req.introduction(), req.playbackTimeLimit(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-6 Display flag 변경")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{partyroomId}/display-flag")
    public ResponseEntity<Void> setDisplayFlag(@PathVariable Long partyroomId,
                                               @Valid @RequestBody UpdateDisplayFlagRequest req) {
        commandService.setDisplayFlag(new PartyroomId(partyroomId), req.flag(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }
}
