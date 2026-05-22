package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.application.ratelimit.BugReportRateLimiter;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * VOC 버그 리포트 submit.
 *
 * Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-4
 * - rate-limit (userId 별 1분 1회)
 * - pageUrl/userAgent server-side truncate to 500
 * - Clock 주입 (KST TZ 정책 [[project_jvm_tz_kst_policy]])
 * - INFO 로그 (observability A4 정합)
 *
 * reporterUserId 는 호출자(controller)가 인증 주체에서 추출해 전달한다. administration 모듈은
 * AuthContext set aspect 가 없으므로(party/user 모듈 한정) ThreadLocalContext 를 쓰지 않고,
 * AdminContext helper 와 동일하게 SecurityContext → controller 경계에서 인증 주체를 넘긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BugReportCommandService {

    private static final int META_MAX_LENGTH = 500;

    private final BugReportRepository repository;
    private final BugReportRateLimiter rateLimiter;
    private final Clock clock;

    @Transactional
    public Long submit(Long reporterUserId, String content, String pageUrl, String userAgent, Long partyroomId) {
        log.info("[bugReport.submit] ENTER requestId={} reporterUserId={} partyroomId={}",
                RequestIdInterceptor.current(), reporterUserId, partyroomId);

        rateLimiter.acquireOrThrow(reporterUserId);

        BugReportData data = BugReportData.create(
                reporterUserId,
                content,
                truncate(pageUrl, META_MAX_LENGTH),
                truncate(userAgent, META_MAX_LENGTH),
                partyroomId,
                LocalDateTime.now(clock));
        BugReportData saved = repository.save(data);

        log.info("[bugReport.submit] OK requestId={} reporterUserId={} bugReportId={}",
                RequestIdInterceptor.current(), reporterUserId, saved.getBugReportId());
        return saved.getBugReportId();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
