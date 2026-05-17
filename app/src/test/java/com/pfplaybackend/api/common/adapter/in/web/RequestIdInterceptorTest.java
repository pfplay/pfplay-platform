package com.pfplaybackend.api.common.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestIdInterceptorTest {

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    RequestIdInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RequestIdInterceptor();
        // Defensive: ensure no leaked ThreadLocal from a prior test on this thread.
        interceptor.afterCompletion(request, response, new Object(), null);
    }

    @Test
    @DisplayName("헤더 부재 시 8자 id 생성 + attr/응답헤더/current() 모두 동일")
    void generates_8char_id_when_header_absent() {
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();

        ArgumentCaptor<String> attrCaptor = ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq(RequestIdInterceptor.REQUEST_ID_ATTR), attrCaptor.capture());
        String generated = attrCaptor.getValue();

        assertThat(generated).isNotNull().hasSize(8);
        verify(response).setHeader(RequestIdInterceptor.REQUEST_ID_HEADER, generated);
        assertThat(RequestIdInterceptor.current()).isEqualTo(generated);
    }

    @Test
    @DisplayName("X-Request-Id 헤더 존재 시 그 값을 그대로 사용(에코)")
    void uses_incoming_header_value_when_present() {
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn("abc123");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(request).setAttribute(RequestIdInterceptor.REQUEST_ID_ATTR, "abc123");
        verify(response).setHeader(RequestIdInterceptor.REQUEST_ID_HEADER, "abc123");
        assertThat(RequestIdInterceptor.current()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("공백 헤더는 부재로 간주 → 생성")
    void blank_header_treated_as_absent() {
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn("   ");

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<String> attrCaptor = ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq(RequestIdInterceptor.REQUEST_ID_ATTR), attrCaptor.capture());
        String generated = attrCaptor.getValue();

        assertThat(generated).isNotBlank().hasSize(8);
        assertThat(generated).isNotEqualTo("   ");
        assertThat(RequestIdInterceptor.current()).isEqualTo(generated);
    }

    @Test
    @DisplayName("afterCompletion 후 current() 는 null (ThreadLocal 정리)")
    void afterCompletion_clears_threadlocal() {
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn("xyz789");
        interceptor.preHandle(request, response, new Object());
        assertThat(RequestIdInterceptor.current()).isEqualTo("xyz789");

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(RequestIdInterceptor.current()).isNull();
    }

    @Test
    @DisplayName("preHandle 호출 없는 스레드에서 current() 는 null")
    void current_is_null_without_preHandle_on_other_thread() throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>("sentinel");
        Thread other = new Thread(() -> seen.set(RequestIdInterceptor.current()));
        other.start();
        other.join();

        assertThat(seen.get()).isNull();
    }
}
