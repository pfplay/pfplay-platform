package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.port.BotAvatarApplyPort;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAssigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BotAvatarAssignerTest {

    private AvatarCatalogQueryUseCase catalog;
    private BotAvatarApplyPort applyPort;
    private Randomizer randomizer;
    private BotAvatarAssigner assigner;

    private static final String FACE_URI = "https://cdn/ava_face_basic_001.png";
    private static final String COMBINABLE_BODY = "https://cdn/ava_body_basic_001.png";
    private static final String STANDALONE_BODY = "https://cdn/ava_body_basic_002.png";

    @BeforeEach
    void setUp() {
        catalog = mock(AvatarCatalogQueryUseCase.class);
        applyPort = mock(BotAvatarApplyPort.class);
        randomizer = mock(Randomizer.class);
        assigner = new BotAvatarAssigner(catalog, applyPort, randomizer);

        // 기본 face = 단일 basic face
        given(catalog.findPublishedFaces())
                .willReturn(List.of(face(FACE_URI)));
        given(catalog.findBodyByUri(COMBINABLE_BODY)).willReturn(Optional.of(body(COMBINABLE_BODY, true)));
        given(catalog.findBodyByUri(STANDALONE_BODY)).willReturn(Optional.of(body(STANDALONE_BODY, false)));
    }

    @Test
    void combinable_바디는_기본_face_를_합성해_적용한다() {
        assigner.assignOne(new UserId(1L), COMBINABLE_BODY);

        ArgumentCaptor<AvatarFaceUri> faceCap = ArgumentCaptor.forClass(AvatarFaceUri.class);
        verify(applyPort).apply(eq(new UserId(1L)), bodyUri(COMBINABLE_BODY), faceCap.capture());
        assertThat(faceCap.getValue().getValue()).isEqualTo(FACE_URI);  // non-empty = BODY_WITH_FACE
    }

    @Test
    void standalone_바디는_빈_face_로_적용한다() {
        assigner.assignOne(new UserId(2L), STANDALONE_BODY);

        ArgumentCaptor<AvatarFaceUri> faceCap = ArgumentCaptor.forClass(AvatarFaceUri.class);
        verify(applyPort).apply(eq(new UserId(2L)), bodyUri(STANDALONE_BODY), faceCap.capture());
        assertThat(faceCap.getValue().getValue()).isEmpty();  // empty = SINGLE_BODY
    }

    @Test
    void distribute_는_셋에서_Randomizer_인덱스로_봇별_배분한다() {
        // 봇 2명, 셋 [standalone, combinable]; randomizer 가 0,1 반환 → 각각 배분
        given(randomizer.nextIndex(2)).willReturn(0, 1);

        var assigned = assigner.distribute(
                List.of(new UserId(1L), new UserId(2L)),
                List.of(STANDALONE_BODY, COMBINABLE_BODY));

        assertThat(assigned).hasSize(2);
        verify(applyPort).apply(eq(new UserId(1L)), bodyUri(STANDALONE_BODY), any());
        verify(applyPort).apply(eq(new UserId(2L)), bodyUri(COMBINABLE_BODY), any());
    }

    @Test
    void distribute_빈_셋이면_INVALID() {
        assertThatThrownBy(() -> assigner.distribute(List.of(new UserId(1L)), List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void distribute_빈_봇목록이면_INVALID() {
        assertThatThrownBy(() -> assigner.distribute(List.of(), List.of(STANDALONE_BODY)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void 카탈로그에_없는_bodyUri_는_INVALID() {
        given(catalog.findBodyByUri("https://cdn/unknown.png")).willReturn(Optional.empty());
        assertThatThrownBy(() -> assigner.assignOne(new UserId(1L), "https://cdn/unknown.png"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void assignRandomFromCatalog_는_published_바디에서_랜덤_1개를_적용한다() {
        given(catalog.findPublishedBodies())
                .willReturn(List.of(body(STANDALONE_BODY, false), body(COMBINABLE_BODY, true)));
        given(randomizer.nextIndex(2)).willReturn(1);

        assigner.assignRandomFromCatalog(new UserId(9L));

        verify(applyPort).apply(eq(new UserId(9L)), bodyUri(COMBINABLE_BODY), any());
    }

    /**
     * AvatarBodyUri 는 value object 이지만 equals 미구현(avatar BC 소관, Chunk 1 범위 외)이라
     * {@code eq(new AvatarBodyUri(...))} 가 동작하지 않는다. value 기준으로 매칭한다.
     */
    private static AvatarBodyUri bodyUri(String expected) {
        return argThat(u -> u != null && expected.equals(u.getValue()));
    }

    private static AvatarBodyDto body(String uri, boolean combinable) {
        return AvatarBodyDto.builder().name("b").resourceUri(uri).iconUri(combinable ? null : uri + ".icon")
                .combinable(combinable).build();
    }

    private static AvatarFaceDto face(String uri) {
        // AvatarFaceDto = 4-arg record (long id, String name, String resourceUri, boolean available)
        return new AvatarFaceDto(1L, "ava_face_basic_001", uri, true);
    }
}
