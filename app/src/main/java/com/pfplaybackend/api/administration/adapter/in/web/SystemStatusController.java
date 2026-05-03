package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.SystemStatusResponse;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementQueryService;
import com.pfplaybackend.api.common.ApiCommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Anonymous public 시스템 상태 endpoint — 클라이언트가 점검/공지 표시에 사용.
 *
 * <p>{@code GET /api/v1/system/status} — auth 불필요, {@code Cache-Control: public, max-age=10}.
 * 백엔드 캐시 없음, HTTP cache header 만으로 부하 완화.
 *
 * <p>SecurityConfig 의 {@code permitAll} 매처에 등록됨 — 미등록 시 anonymous 401.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §4.4
 */
@Tag(name = "System Status API", description = "Anonymous 점검/공지 status")
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemStatusController {

    private final SystemAnnouncementQueryService queryService;

    @Operation(summary = "시스템 상태",
            description = "현재 점검 + 활성 공지(EVENT/EMERGENCY) + 예정 점검. anonymous endpoint, 10초 public cache.")
    @GetMapping("/status")
    public ResponseEntity<ApiCommonResponse<SystemStatusResponse>> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic())
                .body(ApiCommonResponse.success(queryService.getSystemStatus()));
    }
}
