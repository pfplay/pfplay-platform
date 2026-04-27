package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.ChangeAdminPasswordRequest;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.service.AdminPasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin self-service password change. Path moved from spec literal
 * /api/v1/auth/admin/password/change to /api/v1/admin/password/change so
 * PR 4's CookieBearerTokenResolver (matches /api/v1/admin/**) picks the
 * AdminAccessToken cookie. URL rule covered by /api/v1/admin/** → ADMIN.
 */
@Tag(name = "Admin Password API", description = "어드민 셀프 비밀번호 변경 (§5.6)")
@RestController
@RequestMapping("/api/v1/admin/password")
@RequiredArgsConstructor
public class AdminPasswordController {

    private final AdminPasswordService service;
    private final AdminContext adminContext;

    @Operation(summary = "내 비밀번호 변경")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/change")
    public ResponseEntity<Void> change(@Valid @RequestBody ChangeAdminPasswordRequest req) {
        service.changePassword(
                adminContext.currentUserId(),
                req.getCurrentPassword(),
                req.getNewPassword());
        return ResponseEntity.noContent().build();
    }
}
