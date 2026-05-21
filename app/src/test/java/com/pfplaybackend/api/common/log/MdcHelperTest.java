package com.pfplaybackend.api.common.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MdcHelperTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    @DisplayName("scope: 진입 시 MDC put, close 시 remove (이전 값 없을 때)")
    void scope_put_and_remove() {
        try (MdcScope ignored = MdcHelper.scope("partyroomId", 42L)) {
            assertThat(MDC.get("partyroomId")).isEqualTo("42");
        }
        assertThat(MDC.get("partyroomId")).isNull();
    }

    @Test
    @DisplayName("scope: 이전 값 있으면 close 시 복원")
    void scope_restores_previous_value() {
        MDC.put("partyroomId", "10");
        try (MdcScope ignored = MdcHelper.scope("partyroomId", 99L)) {
            assertThat(MDC.get("partyroomId")).isEqualTo("99");
        }
        assertThat(MDC.get("partyroomId")).isEqualTo("10");
    }

    @Test
    @DisplayName("scope: value null 이면 no-op MdcScope (기존 값 무영향)")
    void scope_null_value_is_noop() {
        MDC.put("partyroomId", "existing");
        try (MdcScope ignored = MdcHelper.scope("partyroomId", null)) {
            assertThat(MDC.get("partyroomId")).isEqualTo("existing");
        }
        assertThat(MDC.get("partyroomId")).isEqualTo("existing");
    }

    @Test
    @DisplayName("nested scope: 안쪽 close 시 바깥 값 복원")
    void nested_scope_restores_outer() {
        try (MdcScope outer = MdcHelper.scope("partyroomId", "X")) {
            assertThat(MDC.get("partyroomId")).isEqualTo("X");
            try (MdcScope inner = MdcHelper.scope("partyroomId", "Y")) {
                assertThat(MDC.get("partyroomId")).isEqualTo("Y");
            }
            assertThat(MDC.get("partyroomId")).isEqualTo("X");
        }
        assertThat(MDC.get("partyroomId")).isNull();
    }

    @Test
    @DisplayName("scope: value 가 Long/Integer/String 어떤 타입이든 String 으로 변환")
    void scope_handles_various_value_types() {
        try (MdcScope ignored = MdcHelper.scope("k", 123)) {
            assertThat(MDC.get("k")).isEqualTo("123");
        }
        try (MdcScope ignored = MdcHelper.scope("k", "abc")) {
            assertThat(MDC.get("k")).isEqualTo("abc");
        }
    }
}
