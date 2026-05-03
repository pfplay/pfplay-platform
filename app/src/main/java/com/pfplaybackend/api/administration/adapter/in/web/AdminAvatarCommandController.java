package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.CreateAvatarBodyRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.CreateAvatarFaceRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.PatchAvatarBodyRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.PatchAvatarFaceRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.RetireAvatarResourceRequest;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarFaceView;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogCommandUseCase;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * SUPER_ADMIN 전용 아바타 카탈로그 명령 endpoint. Spec §6.I-2 ~ §6.I-5.
 */
@Tag(name = "Admin Avatar Catalog Command API")
@RequestMapping("/api/v1/admin/avatar")
@RestController
@RequiredArgsConstructor
@Slf4j
public class AdminAvatarCommandController {

    private final AvatarCatalogCommandUseCase commandUseCase;
    private final AdminContext adminContext;

    // -------------------------------------------------------------- create

    @Operation(summary = "Body 리소스 생성", description = "DRAFT 상태로 신규 생성")
    @ApiResponse(responseCode = "201")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping(value = "/bodies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<AdminAvatarBodyView>> createBody(
            @RequestPart("bodyImage") MultipartFile bodyImage,
            @RequestPart(value = "iconImage", required = false) MultipartFile iconImage,
            @Valid @ModelAttribute CreateAvatarBodyRequest req) throws IOException {
        AdminAvatarBodyView view = commandUseCase.createBody(
                req.toCommand(bodyImage, iconImage, adminContext.currentAdministratorId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCommonResponse.success(view));
    }

    @Operation(summary = "Face 리소스 생성", description = "DRAFT 상태로 신규 생성")
    @ApiResponse(responseCode = "201")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping(value = "/faces", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<AdminAvatarFaceView>> createFace(
            @RequestPart("faceImage") MultipartFile faceImage,
            @RequestPart(value = "iconImage", required = false) MultipartFile iconImage,
            @Valid @ModelAttribute CreateAvatarFaceRequest req) throws IOException {
        AdminAvatarFaceView view = commandUseCase.createFace(
                req.toCommand(faceImage, iconImage, adminContext.currentAdministratorId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCommonResponse.success(view));
    }

    // ------------------------------------------------------------- patch

    @Operation(summary = "Body 리소스 부분 수정")
    @ApiResponse(responseCode = "200")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PatchMapping(value = "/bodies/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<AdminAvatarBodyView>> patchBody(
            @Parameter @PathVariable("id") Long id,
            @RequestPart(value = "bodyImage", required = false) MultipartFile bodyImage,
            @RequestPart(value = "iconImage", required = false) MultipartFile iconImage,
            @Valid @ModelAttribute PatchAvatarBodyRequest req) throws IOException {
        AdminAvatarBodyView view = commandUseCase.patchBody(
                req.toCommand(id, bodyImage, iconImage, adminContext.currentAdministratorId()));
        return ResponseEntity.ok(ApiCommonResponse.success(view));
    }

    @Operation(summary = "Face 리소스 부분 수정")
    @ApiResponse(responseCode = "200")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PatchMapping(value = "/faces/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<ApiCommonResponse<AdminAvatarFaceView>> patchFace(
            @Parameter @PathVariable("id") Long id,
            @RequestPart(value = "faceImage", required = false) MultipartFile faceImage,
            @RequestPart(value = "iconImage", required = false) MultipartFile iconImage) throws IOException {
        AdminAvatarFaceView view = commandUseCase.patchFace(
                new PatchAvatarFaceRequest().toCommand(id, faceImage, iconImage,
                        adminContext.currentAdministratorId()));
        return ResponseEntity.ok(ApiCommonResponse.success(view));
    }

    // -------------------------------------------------- icon-only re-upload

    @Operation(summary = "Body 아이콘 재업로드 (DRAFT 전용)")
    @ApiResponse(responseCode = "204")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping(value = "/bodies/{id}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<Void> replaceBodyIcon(
            @PathVariable("id") Long id,
            @RequestPart("iconImage") MultipartFile iconImage) throws IOException {
        commandUseCase.replaceBodyIcon(id, iconImage.getBytes(), iconImage.getContentType(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Face 아이콘 재업로드 (DRAFT 전용)")
    @ApiResponse(responseCode = "204")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping(value = "/faces/{id}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<Void> replaceFaceIcon(
            @PathVariable("id") Long id,
            @RequestPart("iconImage") MultipartFile iconImage) throws IOException {
        commandUseCase.replaceFaceIcon(id, iconImage.getBytes(), iconImage.getContentType(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------- lifecycle 전이

    @Operation(summary = "Body publish", description = "DRAFT → PUBLISHED")
    @ApiResponse(responseCode = "204")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping("/bodies/{id}/publish")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<Void> publishBody(@PathVariable("id") Long id) {
        commandUseCase.publishBody(id, adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Body retire", description = "PUBLISHED → RETIRED. reason 필수.")
    @ApiResponse(responseCode = "204")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping("/bodies/{id}/retire")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<Void> retireBody(
            @PathVariable("id") Long id,
            @Valid @RequestBody RetireAvatarResourceRequest req) {
        commandUseCase.retireBody(id, req.reason(), adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Face publish", description = "DRAFT → PUBLISHED")
    @ApiResponse(responseCode = "204")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping("/faces/{id}/publish")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<Void> publishFace(@PathVariable("id") Long id) {
        commandUseCase.publishFace(id, adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Face retire", description = "PUBLISHED → RETIRED. reason 필수.")
    @ApiResponse(responseCode = "204")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({AvatarException.class})
    @PostMapping("/faces/{id}/retire")
    @PreAuthorize("@adminAuth.canManageAvatarResources()")
    public ResponseEntity<Void> retireFace(
            @PathVariable("id") Long id,
            @Valid @RequestBody RetireAvatarResourceRequest req) {
        commandUseCase.retireFace(id, req.reason(), adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }
}
