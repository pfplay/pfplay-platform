package com.pfplaybackend.api.avatar.domain.entity.data;

import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarBodyResourceDataTest {

    @Test
    @DisplayName("updateResource - 좌표 및 속성이 정상 갱신되어야 한다")
    void updateResourceShouldUpdateAllFields() {
        // given
        AvatarBodyResourceData data = AvatarBodyResourceData.builder()
                .name("ava_body_basic_001")
                .resourceUri("old_uri")
                .obtainableType(ObtainmentType.BASIC)
                .obtainableScore(0)
                .isCombinable(false)
                .isDefaultSetting(false)
                .combinePositionX(0)
                .combinePositionY(0)
                .build();

        // when
        data.updateResource("new_uri", ObtainmentType.BASIC, 0, true, true, 60, 41);

        // then
        assertThat(data.getResourceUri()).isEqualTo("new_uri");
        assertThat(data.isCombinable()).isTrue();
        assertThat(data.isDefaultSetting()).isTrue();
        assertThat(data.getCombinePositionX()).isEqualTo(60);
        assertThat(data.getCombinePositionY()).isEqualTo(41);
    }

    @Test
    @DisplayName("updateResource - 좌표만 변경되는 경우에도 정상 동작")
    void updateResourceShouldUpdatePositionsOnly() {
        // given
        AvatarBodyResourceData data = AvatarBodyResourceData.builder()
                .name("ava_body_djing_003")
                .resourceUri("uri")
                .obtainableType(ObtainmentType.DJ_PNT)
                .obtainableScore(60)
                .isCombinable(true)
                .isDefaultSetting(false)
                .combinePositionX(60)
                .combinePositionY(40)
                .build();

        // when
        data.updateResource("uri", ObtainmentType.DJ_PNT, 60, true, false, 60, 39);

        // then
        assertThat(data.getCombinePositionY()).isEqualTo(39);
        assertThat(data.getCombinePositionX()).isEqualTo(60);
    }
}
