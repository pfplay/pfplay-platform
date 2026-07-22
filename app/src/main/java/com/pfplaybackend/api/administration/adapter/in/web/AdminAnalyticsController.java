package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminAttendanceAnalyticsResponse;
import com.pfplaybackend.api.administration.application.service.AdminGlobalAnalyticsQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #361 어드민 전역 분석 API — 대시보드 v1.
 *
 * <p>경로를 {@code /admin/partyrooms/**} 와 분리한 별도 리소스({@code /admin/analytics})로 둔다 —
 * {@code /admin/partyrooms/{id}/...} 의 경로 변수와 충돌하지 않고, 향후 히트맵/Top룸 등
 * 전역 분석 엔드포인트의 자연스러운 뿌리가 된다. 인증/인가는 어드민 SecurityFilterChain
 * ({@code /api/v1/admin/**})이 담당 — sibling 컨트롤러들과 동일.
 */
@Tag(name = "Admin Global Analytics API", description = "대시보드 v1 — 전역 일별 입퇴장")
@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AdminGlobalAnalyticsQueryService service;

    @GetMapping("/attendance")
    public ResponseEntity<AdminAttendanceAnalyticsResponse> attendance(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "true") boolean excludeBots) {
        return ResponseEntity.ok(service.getAttendance(days, excludeBots));
    }
}
