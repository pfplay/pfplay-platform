package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.application.ratelimit.BugReportRateLimiter;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.common.exception.http.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BugReportCommandServiceTest {

    @Mock BugReportRepository repository;
    @Mock BugReportRateLimiter rateLimiter;

    private BugReportCommandService service;

    private final UserId userId = new UserId(100L);
    private final Clock fixedClock = Clock.fixed(
            Instant.parse("2026-05-21T10:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        service = new BugReportCommandService(repository, rateLimiter, fixedClock);
        AuthContext ctx = mock(AuthContext.class);
        lenient().when(ctx.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(ctx);
        lenient().when(repository.save(any(BugReportData.class))).thenAnswer(inv -> {
            BugReportData input = inv.getArgument(0);
            java.lang.reflect.Field idField = BugReportData.class.getDeclaredField("bugReportId");
            idField.setAccessible(true);
            idField.set(input, 42L);
            return input;
        });
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("submit — happy: 모든 필드 채워 save + id 반환")
    void submitHappy() {
        Long id = service.submit("재생 안 됨", "https://pfplay.xyz/parties/7",
                "Mozilla/5.0", 7L);

        verify(rateLimiter).acquireOrThrow(100L);
        ArgumentCaptor<BugReportData> captor = ArgumentCaptor.forClass(BugReportData.class);
        verify(repository).save(captor.capture());
        BugReportData saved = captor.getValue();
        assertThat(saved.getReporterUserAccountId()).isEqualTo(100L);
        assertThat(saved.getContent()).isEqualTo("재생 안 됨");
        assertThat(saved.getPageUrl()).isEqualTo("https://pfplay.xyz/parties/7");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getPartyroomId()).isEqualTo(7L);
        assertThat(id).isEqualTo(42L);
    }

    @Test
    @DisplayName("submit — pageUrl/UA null 허용")
    void submitWithNullMeta() {
        service.submit("buggy", null, null, null);

        ArgumentCaptor<BugReportData> captor = ArgumentCaptor.forClass(BugReportData.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPageUrl()).isNull();
        assertThat(captor.getValue().getUserAgent()).isNull();
        assertThat(captor.getValue().getPartyroomId()).isNull();
    }

    @Test
    @DisplayName("submit — pageUrl 600자 → server-side truncate to 500")
    void submitTruncatesLongPageUrl() {
        String longUrl = "https://x.com/" + "a".repeat(700);
        service.submit("buggy", longUrl, null, null);

        ArgumentCaptor<BugReportData> captor = ArgumentCaptor.forClass(BugReportData.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPageUrl()).hasSize(500);
        assertThat(captor.getValue().getPageUrl()).startsWith("https://x.com/");
    }

    @Test
    @DisplayName("submit — rate-limit throw → save 0회")
    void submitRateLimitThrows() {
        doThrow(ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED))
                .when(rateLimiter).acquireOrThrow(100L);

        assertThatThrownBy(() -> service.submit("buggy", null, null, null))
                .isInstanceOf(TooManyRequestsException.class);
        verify(repository, never()).save(any());
    }
}
