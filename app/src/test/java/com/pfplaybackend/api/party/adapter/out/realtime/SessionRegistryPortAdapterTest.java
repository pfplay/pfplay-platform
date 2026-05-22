package com.pfplaybackend.api.party.adapter.out.realtime;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionRegistryPortAdapterTest {

    @Mock UserSessionRegistry userSessionRegistry;

    @InjectMocks SessionRegistryPortAdapter adapter;

    @Test
    @DisplayName("register 는 userId 문자열을 UserId 로 변환해 레지스트리에 위임한다")
    void registerDelegatesToRegistry() {
        // when
        adapter.register("s1", "123");

        // then
        verify(userSessionRegistry).register("s1", UserId.fromString("123"));
    }
}
