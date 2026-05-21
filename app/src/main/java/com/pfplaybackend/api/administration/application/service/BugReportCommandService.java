package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.application.ratelimit.BugReportRateLimiter;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
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
    public Long submit(String content, String pageUrl, String userAgent, Long partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        Long userId = authContext.getUserId().getUid();
        log.info("[bugReport.submit] ENTER requestId={} reporterUserId={} partyroomId={}",
                RequestIdInterceptor.current(), userId, partyroomId);

        rateLimiter.acquireOrThrow(userId);

        BugReportData data = BugReportData.create(
                userId,
                content,
                truncate(pageUrl, META_MAX_LENGTH),
                truncate(userAgent, META_MAX_LENGTH),
                partyroomId,
                LocalDateTime.now(clock));
        BugReportData saved = repository.save(data);

        log.info("[bugReport.submit] OK requestId={} reporterUserId={} bugReportId={}",
                RequestIdInterceptor.current(), userId, saved.getBugReportId());
        return saved.getBugReportId();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
