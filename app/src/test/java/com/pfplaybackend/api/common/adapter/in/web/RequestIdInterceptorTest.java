package com.pfplaybackend.api.common.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
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
    @DisplayName("클라 헤더의 제어문자 제거(log-forging/헤더 인젝션 방어)")
    void sanitizes_control_chars_in_client_header() {
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn("a\r\nINJECTED\tx");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();

        ArgumentCaptor<String> attrCaptor = ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq(RequestIdInterceptor.REQUEST_ID_ATTR), attrCaptor.capture());
        String sanitized = attrCaptor.getValue();

        assertThat(sanitized)
                .isEqualTo("aINJECTEDx")
                .doesNotContain("\r", "\n", "\t")
                .doesNotMatch(".*\\p{Cntrl}.*");
        verify(response).setHeader(RequestIdInterceptor.REQUEST_ID_HEADER, "aINJECTEDx");
        assertThat(RequestIdInterceptor.current())
                .isEqualTo("aINJECTEDx")
                .doesNotMatch(".*\\p{Cntrl}.*");
    }

    @Test
    @DisplayName("과도하게 긴 클라 헤더는 64자로 절단(log 증폭 방어)")
    void caps_overlong_client_header() {
        String overlong = "x".repeat(200);
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn(overlong);

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<String> attrCaptor = ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq(RequestIdInterceptor.REQUEST_ID_ATTR), attrCaptor.capture());

        assertThat(attrCaptor.getValue()).hasSize(64);
        assertThat(RequestIdInterceptor.current()).hasSize(64);
    }

    @Test
    @DisplayName("sanitize 후 공백만 남으면 8자 id 생성으로 폴백")
    void blank_after_sanitize_regenerates() {
        when(request.getHeader(RequestIdInterceptor.REQUEST_ID_HEADER)).thenReturn("\n\t\r");

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<String> attrCaptor = ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq(RequestIdInterceptor.REQUEST_ID_ATTR), attrCaptor.capture());
        String generated = attrCaptor.getValue();

        assertThat(generated).isNotBlank().hasSize(8);
        assertThat(RequestIdInterceptor.current()).isEqualTo(generated);
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

    // === Phase B2: MDC 격상 검증 ===

    @AfterEach
    void clearMdcAndSecurityContext() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("preHandle: MDC.requestId 설정")
    void preHandle_sets_mdc_requestId() {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.addHeader("X-Request-Id", "test-req-id-001");
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        interceptor.preHandle(mockRequest, mockResponse, new Object());

        assertThat(MDC.get("requestId")).isEqualTo("test-req-id-001");
    }

    @Test
    @DisplayName("afterCompletion: MDC.requestId + MDC.userId 모두 제거")
    void afterCompletion_removes_mdc() {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-42", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        interceptor.preHandle(mockRequest, mockResponse, new Object());
        assertThat(MDC.get("requestId")).isNotNull();
        assertThat(MDC.get("userId")).isEqualTo("user-42");

        interceptor.afterCompletion(mockRequest, mockResponse, new Object(), null);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("preHandle: 인증된 사용자 — MDC.userId 설정")
    void preHandle_sets_mdc_userId_when_authenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("authenticated-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        interceptor.preHandle(mockRequest, mockResponse, new Object());

        assertThat(MDC.get("userId")).isEqualTo("authenticated-user");
    }

    @Test
    @DisplayName("preHandle: 비인증 (anonymous) — MDC.userId 미설정")
    void preHandle_no_userId_when_anonymous() {
        // SecurityContext 비어있음 (clearContext 후)
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        interceptor.preHandle(mockRequest, mockResponse, new Object());

        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("current(): MDC 위임 (ThreadLocal 제거 후 backward compat)")
    void current_delegates_to_mdc() {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.addHeader("X-Request-Id", "compat-test");
        interceptor.preHandle(mockRequest, new MockHttpServletResponse(), new Object());

        assertThat(RequestIdInterceptor.current()).isEqualTo("compat-test");
    }
}
