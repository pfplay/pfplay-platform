package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.storage.AvatarStorageProperties;
import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarBodyCommand;
import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarFaceCommand;
import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarBodyCommand;
import com.pfplaybackend.api.avatar.application.port.out.AvatarStoragePort;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourcePublished;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceRetired;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarCatalogCommandServiceTest {

    private static final long ADMIN_ID = 100L;
    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};

    private AvatarBodyResourceRepository bodyRepo;
    private AvatarFaceResourceRepository faceRepo;
    private AvatarStoragePort storage;
    private ApplicationEventPublisher events;
    private AvatarCatalogCommandService service;

    @BeforeEach
    void setUp() {
        bodyRepo = mock(AvatarBodyResourceRepository.class);
        faceRepo = mock(AvatarFaceResourceRepository.class);
        storage = mock(AvatarStoragePort.class);
        events = mock(ApplicationEventPublisher.class);
        AvatarStorageProperties props = new AvatarStorageProperties();
        props.setMaxFileSizeBytes(1_000_000L);
        service = new AvatarCatalogCommandService(bodyRepo, faceRepo, storage, props, events);
    }

    private CreateAvatarBodyCommand createBodyCmd() {
        return new CreateAvatarBodyCommand(
                "ava_body_test", PNG_BYTES, "image/png",
                PNG_BYTES, "image/png",
                ObtainmentType.BASIC, 0,
                true, false, 0, 0,
                ADMIN_ID);
    }

    @Test
    @DisplayName("createBody — happy path: GCS 업로드 + repo.save + DRAFT 반환")
    void createBody_happyPath() {
        when(bodyRepo.existsByName("ava_body_test")).thenReturn(false);
        when(storage.upload(eq("ava_body"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_body/x.png");
        when(storage.upload(eq("ava_icon"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_icon/y.png");
        when(bodyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createBody(createBodyCmd());

        assertThat(view.lifecycleStatus()).isEqualTo(LifecycleStatus.DRAFT);
        assertThat(view.resourceUri()).isEqualTo("https://gcs/ava_body/x.png");
        assertThat(view.iconUri()).isEqualTo("https://gcs/ava_icon/y.png");
        assertThat(view.createdBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("createBody — name 중복 시 GCS 업로드도 안 부른다")
    void createBody_dupName_noUpload() {
        when(bodyRepo.existsByName(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.createBody(createBodyCmd()))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_NAME_ALREADY_EXISTS.getErrorCode());
        verify(storage, never()).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("createBody — DB save 실패 시 GCS 객체 정리")
    void createBody_dbFailure_rollsBackGcs() {
        when(bodyRepo.existsByName(anyString())).thenReturn(false);
        when(storage.upload(eq("ava_body"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_body/x.png");
        when(storage.upload(eq("ava_icon"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_icon/y.png");
        when(bodyRepo.save(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.createBody(createBodyCmd()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
        verify(storage).deleteByPublicUrl("https://gcs/ava_body/x.png");
        verify(storage).deleteByPublicUrl("https://gcs/ava_icon/y.png");
    }

    @Test
    @DisplayName("createBody — content-type whitelist 강제")
    void createBody_invalidContentType() {
        var cmd = new CreateAvatarBodyCommand(
                "ava_body_test", PNG_BYTES, "image/gif",
                null, null,
                ObtainmentType.BASIC, 0, true, false, 0, 0, ADMIN_ID);

        assertThatThrownBy(() -> service.createBody(cmd))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_INVALID_FILE_FORMAT.getErrorCode());
    }

    @Test
    @DisplayName("createBody — DRAFT에서 isDefaultSetting=true는 거부")
    void createBody_defaultSettingNotAllowedAtCreate() {
        var cmd = new CreateAvatarBodyCommand(
                "ava_body_x", PNG_BYTES, "image/png", null, null,
                ObtainmentType.BASIC, 0, true, true, 0, 0, ADMIN_ID);

        assertThatThrownBy(() -> service.createBody(cmd))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_INVALID_DEFAULT_SETTING.getErrorCode());
    }

    @Test
    @DisplayName("publishBody — entity.publish + AvatarResourcePublished 이벤트 발행")
    void publishBody_publishesEvent() {
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "n", "uri", "icon", ObtainmentType.BASIC, 0, true, false, 0, 0, ADMIN_ID);
        when(bodyRepo.findById(7L)).thenReturn(Optional.of(body));

        service.publishBody(7L, ADMIN_ID);

        ArgumentCaptor<AvatarResourcePublished> captor = ArgumentCaptor.forClass(AvatarResourcePublished.class);
        verify(events).publishEvent(captor.capture());
        AvatarResourcePublished event = captor.getValue();
        assertThat(event.getResourceType()).isEqualTo(AvatarResourceType.AVATAR_BODY);
        assertThat(event.getAdministratorId()).isEqualTo(ADMIN_ID);
        assertThat(body.getLifecycleStatus()).isEqualTo(LifecycleStatus.PUBLISHED);
    }

    @Test
    @DisplayName("retireBody — 이벤트에 reason 포함")
    void retireBody_eventCarriesReason() {
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "n", "uri", "icon", ObtainmentType.BASIC, 0, true, false, 0, 0, ADMIN_ID);
        body.publish(ADMIN_ID);
        when(bodyRepo.findById(9L)).thenReturn(Optional.of(body));

        service.retireBody(9L, "이미지 오타", ADMIN_ID);

        ArgumentCaptor<AvatarResourceRetired> captor = ArgumentCaptor.forClass(AvatarResourceRetired.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("이미지 오타");
        assertThat(body.getLifecycleStatus()).isEqualTo(LifecycleStatus.RETIRED);
    }

    @Test
    @DisplayName("publishBody — 리소스 없음 → AVATAR_RESOURCE_NOT_FOUND")
    void publishBody_notFound() {
        when(bodyRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publishBody(99L, ADMIN_ID))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_RESOURCE_NOT_FOUND.getErrorCode());
    }

    @Test
    @DisplayName("replaceBodyIcon — 새 업로드 + 기존 GCS 삭제")
    void replaceBodyIcon_swapsAndCleansOld() {
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "n", "uri", "old-icon", ObtainmentType.BASIC, 0, true, false, 0, 0, ADMIN_ID);
        when(bodyRepo.findById(5L)).thenReturn(Optional.of(body));
        when(storage.upload(eq("ava_icon"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_icon/new.png");

        service.replaceBodyIcon(5L, PNG_BYTES, "image/png", ADMIN_ID);

        assertThat(body.getIconUri()).isEqualTo("https://gcs/ava_icon/new.png");
        verify(storage).deleteByPublicUrl("old-icon");
    }

    @Test
    @DisplayName("replaceBodyIcon — entity validation 실패 시 새 GCS 객체 정리")
    void replaceBodyIcon_entityRejectsRollsBack() {
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "n", "uri", "old-icon", ObtainmentType.BASIC, 0, true, false, 0, 0, ADMIN_ID);
        body.publish(ADMIN_ID);  // PUBLISHED → replaceIconUri 거부
        when(bodyRepo.findById(5L)).thenReturn(Optional.of(body));
        when(storage.upload(eq("ava_icon"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_icon/new.png");

        assertThatThrownBy(() -> service.replaceBodyIcon(5L, PNG_BYTES, "image/png", ADMIN_ID))
                .isInstanceOf(AbstractHTTPException.class);
        verify(storage).deleteByPublicUrl("https://gcs/ava_icon/new.png");
        verify(storage, never()).deleteByPublicUrl("old-icon");
    }

    @Test
    @DisplayName("createFace — happy path")
    void createFace_happyPath() {
        when(faceRepo.existsByName(anyString())).thenReturn(false);
        when(storage.upload(eq("ava_face"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_face/f.png");
        when(faceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAvatarFaceCommand(
                "ava_face_x", PNG_BYTES, "image/png", null, null, ADMIN_ID);

        var view = service.createFace(cmd);

        assertThat(view.lifecycleStatus()).isEqualTo(LifecycleStatus.DRAFT);
        assertThat(view.resourceUri()).isEqualTo("https://gcs/ava_face/f.png");
        assertThat(view.iconUri()).isNull();
        verify(faceRepo).save(any(AvatarFaceResourceData.class));
    }

    @Test
    @DisplayName("patchBody — 이미지 미제공 시 GCS 호출 없이 메타데이터만 업데이트")
    void patchBody_metadataOnly() {
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "n", "old-uri", "old-icon", ObtainmentType.BASIC, 0, false, false, 0, 0, ADMIN_ID);
        when(bodyRepo.findById(3L)).thenReturn(Optional.of(body));

        var cmd = new PatchAvatarBodyCommand(
                3L, ObtainmentType.DJ_PNT, 60, true, false, 60, 41,
                null, null, null, null, ADMIN_ID);

        service.patchBody(cmd);

        assertThat(body.getObtainableType()).isEqualTo(ObtainmentType.DJ_PNT);
        assertThat(body.getObtainableScore()).isEqualTo(60);
        assertThat(body.getCombinePositionY()).isEqualTo(41);
        assertThat(body.getResourceUri()).isEqualTo("old-uri");
        verify(storage, never()).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("createBody — body 업로드 OK, icon 업로드 실패 시 body 객체 즉시 정리")
    void createBody_iconUploadFails_cleansBodyUpload() {
        when(bodyRepo.existsByName(anyString())).thenReturn(false);
        when(storage.upload(eq("ava_body"), anyString(), anyString(), any()))
                .thenReturn("https://gcs/ava_body/x.png");
        doThrow(new RuntimeException("icon GCS down"))
                .when(storage).upload(eq("ava_icon"), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.createBody(createBodyCmd()))
                .isInstanceOf(RuntimeException.class);
        verify(storage, atLeastOnce()).deleteByPublicUrl("https://gcs/ava_body/x.png");
    }

    // org.mockito.ArgumentMatchers.eq alias — 명시 import 부담 줄이려고 helper로 분리
    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }
}
