package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarFaceView;
import com.pfplaybackend.api.avatar.application.port.in.AvatarAdminCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 어드민 아바타 카탈로그 조회 endpoint. Spec §6.I-1.
 */
@Tag(name = "Admin Avatar Catalog Query API")
@RequestMapping("/api/v1/admin/avatar")
@RestController
@RequiredArgsConstructor
public class AdminAvatarQueryController {

    private final AvatarAdminCatalogQueryUseCase queryUseCase;

    @Operation(summary = "Body 카탈로그 조회 (필터)")
    @ApiResponse(responseCode = "200")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/bodies")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<List<AdminAvatarBodyView>>> listBodies(
            @RequestParam(value = "status", required = false) LifecycleStatus status,
            @RequestParam(value = "obtainableType", required = false) ObtainmentType type) {
        return ResponseEntity.ok(ApiCommonResponse.success(queryUseCase.listBodies(status, type)));
    }

    @Operation(summary = "Face 카탈로그 조회 (필터)")
    @ApiResponse(responseCode = "200")
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/faces")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<List<AdminAvatarFaceView>>> listFaces(
            @RequestParam(value = "status", required = false) LifecycleStatus status) {
        return ResponseEntity.ok(ApiCommonResponse.success(queryUseCase.listFaces(status)));
    }

    @Operation(summary = "Body 단건 조회")
    @ApiResponse(responseCode = "200")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @GetMapping("/bodies/{id}")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<AdminAvatarBodyView>> getBody(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiCommonResponse.success(queryUseCase.getBody(id)));
    }

    @Operation(summary = "Face 단건 조회")
    @ApiResponse(responseCode = "200")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @GetMapping("/faces/{id}")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<AdminAvatarFaceView>> getFace(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiCommonResponse.success(queryUseCase.getFace(id)));
    }
}
