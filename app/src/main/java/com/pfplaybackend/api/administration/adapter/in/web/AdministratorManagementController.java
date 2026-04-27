package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.CreateAdministratorRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdministratorListResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.CreateAdministratorResponse;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.service.AdministratorManagementService;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Admin Administrator Management API",
     description = "F-1 어드민 거버넌스 (슈퍼어드민 전용)")
@RestController
@RequestMapping("/api/v1/admin/system/administrators")
@RequiredArgsConstructor
public class AdministratorManagementController {

    private final AdministratorManagementService service;
    private final AdminContext adminContext;

    @Operation(summary = "어드민 목록")
    @PreAuthorize("@adminAuth.canManageAdmins()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<AdministratorListResponse>> list(
            @RequestParam(required = false) AdminRole role,
            @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                service.list(role, includeRevoked)));
    }

    @Operation(summary = "어드민 생성")
    @PreAuthorize("@adminAuth.canManageAdmins()")
    @PostMapping
    public ResponseEntity<ApiCommonResponse<CreateAdministratorResponse>> create(
            @Valid @RequestBody CreateAdministratorRequest req) {
        Long actorId = adminContext.currentAdministratorId();
        return ResponseEntity.ok(ApiCommonResponse.success(
                service.create(req, actorId)));
    }
}
