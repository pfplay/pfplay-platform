package com.pfplaybackend.api.common.config;

import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebMvcConfigTest {

    @Mock
    InterceptorRegistry registry;

    @Mock
    InterceptorRegistration registration;

    @Test
    @DisplayName("addInterceptors 가 RequestIdInterceptor 를 등록한다")
    void registers_request_id_interceptor() {
        RequestIdInterceptor interceptor = new RequestIdInterceptor();
        WebMvcConfig config = new WebMvcConfig(interceptor);

        when(registry.addInterceptor(interceptor)).thenReturn(registration);

        config.addInterceptors(registry);

        verify(registry).addInterceptor(interceptor);
    }
}
