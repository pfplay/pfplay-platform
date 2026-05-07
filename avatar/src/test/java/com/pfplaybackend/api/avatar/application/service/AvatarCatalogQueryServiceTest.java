package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarCatalogQueryServiceTest {

    @Mock AvatarBodyResourceRepository bodyRepo;
    @Mock AvatarFaceResourceRepository faceRepo;
    @InjectMocks AvatarCatalogQueryService service;

    private AvatarBodyResourceData publishedBody(String name, String resourceUri, String iconUri) {
        return AvatarBodyResourceData.builder()
                .id(1L).name(name).resourceUri(resourceUri).iconUri(iconUri)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .isCombinable(true).isDefaultSetting(true)
                .combinePositionX(0).combinePositionY(0)
                .lifecycleStatus(LifecycleStatus.PUBLISHED)
                .build();
    }

    private AvatarFaceResourceData publishedFace(String name, String resourceUri, String iconUri) {
        return AvatarFaceResourceData.builder()
                .id(2L).name(name).resourceUri(resourceUri).iconUri(iconUri)
                .obtainableType(ObtainmentType.BASIC)
                .lifecycleStatus(LifecycleStatus.PUBLISHED)
                .build();
    }

    @Test
    @DisplayName("findPublishedBodies — PUBLISHED 라이프사이클로 필터링한다")
    void findPublishedBodiesFiltersByLifecycle() {
        AvatarBodyResourceData body = publishedBody("ava_body_basic_001", "body_uri", "icon_uri");
        when(bodyRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED))
                .thenReturn(List.of(body));

        List<AvatarBodyDto> result = service.findPublishedBodies();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLifecycleStatus()).isEqualTo(LifecycleStatus.PUBLISHED);
        assertThat(result.get(0).getResourceUri()).isEqualTo("body_uri");
        assertThat(result.get(0).getIconUri()).isEqualTo("icon_uri");
    }

    @Test
    @DisplayName("findPublishedFaces — PUBLISHED 라이프사이클로 필터링한다")
    void findPublishedFacesFiltersByLifecycle() {
        AvatarFaceResourceData face = publishedFace("ava_face_basic_001", "face_uri", "icon_uri");
        when(faceRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED))
                .thenReturn(List.of(face));

        List<AvatarFaceDto> result = service.findPublishedFaces();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).resourceUri()).isEqualTo("face_uri");
        assertThat(result.get(0).available()).isTrue();
    }

    @Test
    @DisplayName("findBodyByUri — 존재하지 않으면 빈 Optional 반환")
    void findBodyByUriEmptyWhenMissing() {
        when(bodyRepo.findOneAvatarResourceByResourceUri("missing")).thenReturn(null);
        assertThat(service.findBodyByUri("missing")).isEmpty();
    }

    @Test
    @DisplayName("findFaceByUri — 존재하면 DTO를 반환")
    void findFaceByUriPresent() {
        AvatarFaceResourceData face = publishedFace("ava_face_basic_001", "face_uri", "icon_uri");
        when(faceRepo.findOneAvatarResourceByResourceUri("face_uri")).thenReturn(face);

        Optional<AvatarFaceDto> result = service.findFaceByUri("face_uri");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("ava_face_basic_001");
    }

    @Test
    @DisplayName("findDefaultBody — defaultSetting=true 바디를 DTO로 반환")
    void findDefaultBodyReturnsDefault() {
        AvatarBodyResourceData defaultBody = publishedBody("ava_body_basic_001", "default_uri", "icon");
        when(bodyRepo.getDefaultSettingResource()).thenReturn(Optional.of(defaultBody));

        AvatarBodyDto result = service.findDefaultBody();

        assertThat(result.getResourceUri()).isEqualTo("default_uri");
    }

    @Test
    @DisplayName("findDefaultBody — 존재하지 않으면 IllegalStateException")
    void findDefaultBodyThrowsWhenAbsent() {
        when(bodyRepo.getDefaultSettingResource()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findDefaultBody())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("findBodyIconUriByName — 매칭되는 바디의 iconUri 반환")
    void findBodyIconUriByNameReturnsIconUri() {
        AvatarBodyResourceData body = publishedBody("ava_body_x", "body_uri", "icon_uri_X");
        when(bodyRepo.findByName("ava_body_x")).thenReturn(Optional.of(body));

        assertThat(service.findBodyIconUriByName("ava_body_x")).isEqualTo("icon_uri_X");
    }

    @Test
    @DisplayName("findBodyIconUriByName — 바디 부재 시 null")
    void findBodyIconUriByNameNullWhenAbsent() {
        when(bodyRepo.findByName("unknown")).thenReturn(Optional.empty());
        assertThat(service.findBodyIconUriByName("unknown")).isNull();
    }

    @Test
    @DisplayName("findFaceIconUriByName — 매칭되는 페이스의 iconUri 반환")
    void findFaceIconUriByNameReturnsIconUri() {
        AvatarFaceResourceData face = publishedFace("ava_face_y", "face_uri", "icon_uri_Y");
        when(faceRepo.findByName("ava_face_y")).thenReturn(Optional.of(face));

        assertThat(service.findFaceIconUriByName("ava_face_y")).isEqualTo("icon_uri_Y");
    }

    @Test
    @DisplayName("isBasicFaceUri — 페이스 URI 존재 여부 반환")
    void isBasicFaceUriDelegates() {
        AvatarFaceResourceData face = publishedFace("ava_face_basic_001", "basic_uri", null);
        when(faceRepo.findByResourceUri("basic_uri")).thenReturn(Optional.of(face));
        when(faceRepo.findByResourceUri("custom_uri")).thenReturn(Optional.empty());

        assertThat(service.isBasicFaceUri("basic_uri")).isTrue();
        assertThat(service.isBasicFaceUri("custom_uri")).isFalse();
    }
}
