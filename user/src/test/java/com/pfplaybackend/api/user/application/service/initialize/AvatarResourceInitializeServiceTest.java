package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.user.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.AvatarIconResourceRepository;
import com.pfplaybackend.api.user.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.user.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.user.domain.entity.data.AvatarIconResourceData;
import com.pfplaybackend.api.user.domain.enums.PairType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvatarResourceInitializeServiceTest {

    @Mock AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Mock AvatarFaceResourceRepository avatarFaceResourceRepository;
    @Mock AvatarIconResourceRepository avatarIconResourceRepository;

    @InjectMocks AvatarResourceInitializeService avatarResourceInitializeService;

    // ── Body ──

    @Test
    @DisplayName("addAvatarBodies — 기존 리소스가 없으면 새로 생성한다")
    void addAvatarBodiesCreatesNew() {
        // given
        when(avatarBodyResourceRepository.findByName(anyString())).thenReturn(Optional.empty());

        // when
        avatarResourceInitializeService.addAvatarBodies();

        // then
        verify(avatarBodyResourceRepository, times(15)).save(any(AvatarBodyResourceData.class));
    }

    @Test
    @DisplayName("addAvatarBodies — 기존 리소스가 있으면 스킵한다 (코드가 데이터를 덮어쓰지 않음)")
    void addAvatarBodiesSkipsExisting() {
        // given
        AvatarBodyResourceData existing = mock(AvatarBodyResourceData.class);
        when(avatarBodyResourceRepository.findByName(anyString())).thenReturn(Optional.of(existing));

        // when
        avatarResourceInitializeService.addAvatarBodies();

        // then — save도, 기존 인스턴스 update도 호출되지 않음
        verify(avatarBodyResourceRepository, never()).save(any(AvatarBodyResourceData.class));
        verifyNoInteractions(existing);
    }

    // ── Face ──

    @Test
    @DisplayName("addAvatarFaces — 기존 리소스가 없으면 저장한다")
    void addAvatarFacesCreatesNew() {
        // given
        when(avatarFaceResourceRepository.findByResourceUri(anyString())).thenReturn(Optional.empty());

        // when
        avatarResourceInitializeService.addAvatarFaces();

        // then
        verify(avatarFaceResourceRepository, times(1)).save(any(AvatarFaceResourceData.class));
    }

    @Test
    @DisplayName("addAvatarFaces — 기존 리소스가 있으면 스킵한다")
    void addAvatarFacesSkipsExisting() {
        // given
        AvatarFaceResourceData existing = mock(AvatarFaceResourceData.class);
        when(avatarFaceResourceRepository.findByResourceUri(anyString())).thenReturn(Optional.of(existing));

        // when
        avatarResourceInitializeService.addAvatarFaces();

        // then
        verify(avatarFaceResourceRepository, never()).save(any(AvatarFaceResourceData.class));
    }

    // ── Icon ──

    @Test
    @DisplayName("addAvatarIcons — 기존 리소스가 없으면 저장한다")
    void addAvatarIconsCreatesNew() {
        // given — Mockito 기본값 null → SKIP 분기 미진입
        when(avatarIconResourceRepository.findByNameAndPairType(anyString(), any(PairType.class))).thenReturn(null);

        // when
        avatarResourceInitializeService.addAvatarIcons();

        // then
        verify(avatarIconResourceRepository, times(5)).save(any(AvatarIconResourceData.class));
    }

    @Test
    @DisplayName("addAvatarIcons — 기존 리소스가 있으면 스킵한다")
    void addAvatarIconsSkipsExisting() {
        // given
        AvatarIconResourceData existing = mock(AvatarIconResourceData.class);
        when(avatarIconResourceRepository.findByNameAndPairType(anyString(), any(PairType.class))).thenReturn(existing);

        // when
        avatarResourceInitializeService.addAvatarIcons();

        // then
        verify(avatarIconResourceRepository, never()).save(any(AvatarIconResourceData.class));
    }
}
