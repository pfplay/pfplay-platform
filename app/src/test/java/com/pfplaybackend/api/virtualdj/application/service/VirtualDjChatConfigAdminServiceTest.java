package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.virtualdj.application.dto.ChatConfigView;
import com.pfplaybackend.api.virtualdj.application.event.VirtualDjChatConfigChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VirtualDjChatConfigAdminService} 단위 테스트 — 6키 read/update.
 *
 * <p>read: 전체 존재 / 누락→DEFAULT / 깨진 값→DEFAULT.
 * update: 검증 우선(쓰기 전 실패), 6 save + 1 publish + adminId 전달.
 */
@ExtendWith(MockitoExtension.class)
class VirtualDjChatConfigAdminServiceTest {

    @Mock private SystemConfigRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AdminContext adminContext;

    @InjectMocks private VirtualDjChatConfigAdminService service;

    private static SystemConfigData row(String key, String value) {
        return SystemConfigData.create(key, value, "desc", 1L);
    }

    // ── read ──

    @Test
    @DisplayName("read: 6키 전부 존재하면 그 값을 파싱해 반환")
    void read_allPresent() {
        when(repository.findByConfigKey("vdj.chat.enabled")).thenReturn(Optional.of(row("vdj.chat.enabled", "true")));
        when(repository.findByConfigKey("vdj.playlist.self_update.enabled")).thenReturn(Optional.of(row("vdj.playlist.self_update.enabled", "true")));
        when(repository.findByConfigKey("vdj.chat.trigger.probability")).thenReturn(Optional.of(row("vdj.chat.trigger.probability", "40")));
        when(repository.findByConfigKey("vdj.chat.room.cooldown.seconds")).thenReturn(Optional.of(row("vdj.chat.room.cooldown.seconds", "60")));
        when(repository.findByConfigKey("vdj.chat.context.size")).thenReturn(Optional.of(row("vdj.chat.context.size", "10")));
        when(repository.findByConfigKey("vdj.chat.output.max.tokens")).thenReturn(Optional.of(row("vdj.chat.output.max.tokens", "512")));

        ChatConfigView view = service.read();

        assertThat(view.chatEnabled()).isTrue();
        assertThat(view.selfUpdateEnabled()).isTrue();
        assertThat(view.probabilityPercent()).isEqualTo(40);
        assertThat(view.cooldownSeconds()).isEqualTo(60);
        assertThat(view.contextSize()).isEqualTo(10);
        assertThat(view.outputMaxTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("read: 행이 모두 누락이면 코드 DEFAULT 반환")
    void read_missingFallsBackToDefault() {
        when(repository.findByConfigKey(any())).thenReturn(Optional.empty());

        ChatConfigView view = service.read();

        assertThat(view.chatEnabled()).isFalse();
        assertThat(view.selfUpdateEnabled()).isFalse();
        assertThat(view.probabilityPercent()).isEqualTo(12);
        assertThat(view.cooldownSeconds()).isEqualTo(30);
        assertThat(view.contextSize()).isEqualTo(20);
        assertThat(view.outputMaxTokens()).isEqualTo(256);
    }

    @Test
    @DisplayName("read: 값이 깨졌으면(파싱 불가/빈값) DEFAULT 로 폴백")
    void read_malformedFallsBackToDefault() {
        when(repository.findByConfigKey("vdj.chat.enabled")).thenReturn(Optional.of(row("vdj.chat.enabled", "yes")));
        when(repository.findByConfigKey("vdj.playlist.self_update.enabled")).thenReturn(Optional.of(row("vdj.playlist.self_update.enabled", "  ")));
        when(repository.findByConfigKey("vdj.chat.trigger.probability")).thenReturn(Optional.of(row("vdj.chat.trigger.probability", "abc")));
        when(repository.findByConfigKey("vdj.chat.room.cooldown.seconds")).thenReturn(Optional.of(row("vdj.chat.room.cooldown.seconds", "")));
        when(repository.findByConfigKey("vdj.chat.context.size")).thenReturn(Optional.of(row("vdj.chat.context.size", "x")));
        when(repository.findByConfigKey("vdj.chat.output.max.tokens")).thenReturn(Optional.of(row("vdj.chat.output.max.tokens", "NaN")));

        ChatConfigView view = service.read();

        assertThat(view.chatEnabled()).isFalse();
        assertThat(view.selfUpdateEnabled()).isFalse();
        assertThat(view.probabilityPercent()).isEqualTo(12);
        assertThat(view.cooldownSeconds()).isEqualTo(30);
        assertThat(view.contextSize()).isEqualTo(20);
        assertThat(view.outputMaxTokens()).isEqualTo(256);
    }

    @Test
    @DisplayName("read: boolean 은 trim + 대소문자 무시")
    void read_booleanCaseInsensitiveTrim() {
        when(repository.findByConfigKey("vdj.chat.enabled")).thenReturn(Optional.of(row("vdj.chat.enabled", "  TRUE ")));
        when(repository.findByConfigKey("vdj.playlist.self_update.enabled")).thenReturn(Optional.of(row("vdj.playlist.self_update.enabled", "False")));
        when(repository.findByConfigKey("vdj.chat.trigger.probability")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.room.cooldown.seconds")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.context.size")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.output.max.tokens")).thenReturn(Optional.empty());

        ChatConfigView view = service.read();

        assertThat(view.chatEnabled()).isTrue();
        assertThat(view.selfUpdateEnabled()).isFalse();
    }

    // ── update happy ──

    @Test
    @DisplayName("update happy: 6키 save + 1 publish + adminId 전달")
    void update_happy() {
        when(adminContext.currentAdministratorId()).thenReturn(7L);
        when(repository.findByConfigKey(any())).thenReturn(Optional.empty());

        service.update(true, true, 50, 45, 15, 300);

        ArgumentCaptor<SystemConfigData> captor = ArgumentCaptor.forClass(SystemConfigData.class);
        verify(repository, times(6)).save(captor.capture());

        List<SystemConfigData> saved = captor.getAllValues();
        assertThat(saved).extracting(SystemConfigData::getConfigKey)
                .containsExactlyInAnyOrder(
                        "vdj.chat.enabled",
                        "vdj.playlist.self_update.enabled",
                        "vdj.chat.trigger.probability",
                        "vdj.chat.room.cooldown.seconds",
                        "vdj.chat.context.size",
                        "vdj.chat.output.max.tokens");
        assertThat(saved).allSatisfy(d ->
                assertThat(d.getUpdatedByAdministratorId()).isEqualTo(7L));

        assertThat(valueOf(saved, "vdj.chat.enabled")).isEqualTo("true");
        assertThat(valueOf(saved, "vdj.playlist.self_update.enabled")).isEqualTo("true");
        assertThat(valueOf(saved, "vdj.chat.trigger.probability")).isEqualTo("50");
        assertThat(valueOf(saved, "vdj.chat.room.cooldown.seconds")).isEqualTo("45");
        assertThat(valueOf(saved, "vdj.chat.context.size")).isEqualTo("15");
        assertThat(valueOf(saved, "vdj.chat.output.max.tokens")).isEqualTo("300");

        verify(eventPublisher, times(1)).publishEvent(any(VirtualDjChatConfigChangedEvent.class));
    }

    @Test
    @DisplayName("update: 기존 행이 있으면 updateValue 로 갱신해 save")
    void update_existingRowUpdatedInPlace() {
        when(adminContext.currentAdministratorId()).thenReturn(9L);
        SystemConfigData existing = row("vdj.chat.enabled", "false");
        when(repository.findByConfigKey("vdj.chat.enabled")).thenReturn(Optional.of(existing));
        when(repository.findByConfigKey("vdj.playlist.self_update.enabled")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.trigger.probability")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.room.cooldown.seconds")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.context.size")).thenReturn(Optional.empty());
        when(repository.findByConfigKey("vdj.chat.output.max.tokens")).thenReturn(Optional.empty());

        service.update(true, false, 12, 30, 20, 256);

        assertThat(existing.getConfigValue()).isEqualTo("true");
        assertThat(existing.getUpdatedByAdministratorId()).isEqualTo(9L);
        verify(repository, times(6)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(VirtualDjChatConfigChangedEvent.class));
    }

    // ── update validation (fail before any write) ──

    @Test
    @DisplayName("update: probability 101 → 예외 + save/publish 0")
    void update_probabilityTooHigh() {
        assertThatThrownBy(() -> service.update(true, false, 101, 30, 20, 256))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("update: cooldown 0 → 예외 + save 0")
    void update_cooldownTooLow() {
        assertThatThrownBy(() -> service.update(true, false, 12, 0, 20, 256))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("update: context 0 → 예외 + save 0")
    void update_contextTooLow() {
        assertThatThrownBy(() -> service.update(true, false, 12, 30, 0, 256))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("update: maxTokens 0 → 예외 + save 0")
    void update_maxTokensTooLow() {
        assertThatThrownBy(() -> service.update(true, false, 12, 30, 20, 0))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    private static String valueOf(List<SystemConfigData> saved, String key) {
        return saved.stream()
                .filter(d -> d.getConfigKey().equals(key))
                .findFirst().orElseThrow()
                .getConfigValue();
    }
}
