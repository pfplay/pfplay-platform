package com.pfplaybackend.api.notification;

import com.pfplaybackend.api.notification.adapter.out.push.MartijndwarsWebPushAdapter;
import com.pfplaybackend.api.notification.application.port.WebPushSender.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MartijndwarsWebPushAdapterTest {

    @Test
    @DisplayName("2xx 상태코드는 OK 로 매핑된다")
    void maps2xxToOk() {
        assertThat(MartijndwarsWebPushAdapter.mapStatus(200)).isEqualTo(Result.OK);
        assertThat(MartijndwarsWebPushAdapter.mapStatus(201)).isEqualTo(Result.OK);
    }

    @Test
    @DisplayName("404/410 은 GONE(구독 prune 대상)으로 매핑된다")
    void maps404And410ToGone() {
        assertThat(MartijndwarsWebPushAdapter.mapStatus(404)).isEqualTo(Result.GONE);
        assertThat(MartijndwarsWebPushAdapter.mapStatus(410)).isEqualTo(Result.GONE);
    }

    @Test
    @DisplayName("그 외 상태코드(5xx 등)는 FAILED 로 매핑된다")
    void mapsOtherToFailed() {
        assertThat(MartijndwarsWebPushAdapter.mapStatus(500)).isEqualTo(Result.FAILED);
        assertThat(MartijndwarsWebPushAdapter.mapStatus(400)).isEqualTo(Result.FAILED);
        assertThat(MartijndwarsWebPushAdapter.mapStatus(429)).isEqualTo(Result.FAILED);
    }
}
