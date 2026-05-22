package com.pfplaybackend.api.administration.domain.entity.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BugReportDataTest {

    @Test
    @DisplayName("create — 모든 필드 설정")
    void createSetsAllFields() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 21, 10, 0);
        BugReportData data = BugReportData.create(
                100L, "재생이 안 됩니다", "https://pfplay.xyz/parties/7",
                "Mozilla/5.0", 7L, now);

        assertThat(data.getReporterUserAccountId()).isEqualTo(100L);
        assertThat(data.getContent()).isEqualTo("재생이 안 됩니다");
        assertThat(data.getPageUrl()).isEqualTo("https://pfplay.xyz/parties/7");
        assertThat(data.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(data.getPartyroomId()).isEqualTo(7L);
        assertThat(data.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("create — nullable 메타(pageUrl/UA/partyroomId) null 허용")
    void createWithNullableMeta() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 21, 10, 0);
        BugReportData data = BugReportData.create(100L, "...", null, null, null, now);

        assertThat(data.getPageUrl()).isNull();
        assertThat(data.getUserAgent()).isNull();
        assertThat(data.getPartyroomId()).isNull();
    }
}
