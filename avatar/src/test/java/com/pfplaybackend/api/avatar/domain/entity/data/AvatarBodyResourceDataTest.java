package com.pfplaybackend.api.avatar.domain.entity.data;

import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarBodyResourceDataTest {

    private static final long ADMIN_ID = 42L;

    private AvatarBodyResourceData draft() {
        return AvatarBodyResourceData.draft(
                "ava_body_test_001", "https://gcs/body.png", "https://gcs/icon.png",
                ObtainmentType.BASIC, 0, true, false, 0, 0, ADMIN_ID);
    }

    private AvatarBodyResourceData published() {
        AvatarBodyResourceData d = draft();
        d.publish(ADMIN_ID);
        return d;
    }

    @Nested
    @DisplayName("publish/retire 라이프사이클")
    class Lifecycle {

        @Test
        @DisplayName("publish — DRAFT에서 PUBLISHED로 전이")
        void publish_draftToPublished() {
            AvatarBodyResourceData d = draft();

            LocalDateTime before = d.getUpdatedAt();
            d.publish(ADMIN_ID);

            assertThat(d.getLifecycleStatus()).isEqualTo(LifecycleStatus.PUBLISHED);
            assertThat(d.getUpdatedBy()).isEqualTo(ADMIN_ID);
            assertThat(d.getUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("publish — 이미 PUBLISHED인 경우 거부")
        void publish_rejectsWhenAlreadyPublished() {
            AvatarBodyResourceData d = published();

            assertThatThrownBy(() -> d.publish(ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class)
                    .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                    .isEqualTo(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION.getErrorCode());
        }

        @Test
        @DisplayName("retire — PUBLISHED만 허용. DRAFT/RETIRED 거부")
        void retire_publishedOnly() {
            AvatarBodyResourceData draft = draft();
            assertThatThrownBy(() -> draft.retire(ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class);

            AvatarBodyResourceData pub = published();
            pub.retire(ADMIN_ID);
            assertThat(pub.getLifecycleStatus()).isEqualTo(LifecycleStatus.RETIRED);

            assertThatThrownBy(() -> pub.retire(ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class);
        }
    }

    @Nested
    @DisplayName("updateMetadata invariant")
    class UpdateMetadata {

        @Test
        @DisplayName("BASIC + score!=0 → AVATAR_INVALID_DEFAULT_SETTING")
        void basicMustHaveZeroScore() {
            AvatarBodyResourceData d = draft();

            assertThatThrownBy(() -> d.updateMetadata(
                    ObtainmentType.BASIC, 50, true, false, 0, 0, ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class)
                    .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                    .isEqualTo(AvatarException.AVATAR_INVALID_DEFAULT_SETTING.getErrorCode());
        }

        @Test
        @DisplayName("isDefaultSetting=true 이지만 PUBLISHED 아님 → 거부")
        void defaultSettingRequiresPublished() {
            AvatarBodyResourceData d = draft();

            assertThatThrownBy(() -> d.updateMetadata(
                    ObtainmentType.BASIC, 0, true, true, 0, 0, ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class)
                    .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                    .isEqualTo(AvatarException.AVATAR_INVALID_DEFAULT_SETTING.getErrorCode());
        }

        @Test
        @DisplayName("isDefaultSetting=true + DJ_PNT → 거부")
        void defaultSettingRequiresBasic() {
            AvatarBodyResourceData d = published();

            assertThatThrownBy(() -> d.updateMetadata(
                    ObtainmentType.DJ_PNT, 60, true, true, 60, 40, ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class);
        }

        @Test
        @DisplayName("PUBLISHED 상태에서 BASIC + isDefaultSetting=true 허용")
        void allowsDefaultSettingWhenPublishedBasic() {
            AvatarBodyResourceData d = published();

            d.updateMetadata(ObtainmentType.BASIC, 0, true, true, 60, 41, ADMIN_ID);

            assertThat(d.isDefaultSetting()).isTrue();
            assertThat(d.getCombinePositionX()).isEqualTo(60);
            assertThat(d.getCombinePositionY()).isEqualTo(41);
            assertThat(d.getUpdatedBy()).isEqualTo(ADMIN_ID);
        }

        @Test
        @DisplayName("RETIRED 상태에서는 메타데이터 수정 거부")
        void retiredRejectsMutation() {
            AvatarBodyResourceData d = published();
            d.retire(ADMIN_ID);

            assertThatThrownBy(() -> d.updateMetadata(
                    ObtainmentType.BASIC, 0, false, false, 0, 0, ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class)
                    .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                    .isEqualTo(AvatarException.AVATAR_RESOURCE_RETIRED.getErrorCode());
        }
    }

    @Nested
    @DisplayName("이미지 URI 교체")
    class ImageReplace {

        @Test
        @DisplayName("DRAFT — replaceResourceUri 허용")
        void replaceResource_draft() {
            AvatarBodyResourceData d = draft();

            d.replaceResourceUri("https://gcs/new-body.png", ADMIN_ID);

            assertThat(d.getResourceUri()).isEqualTo("https://gcs/new-body.png");
        }

        @Test
        @DisplayName("PUBLISHED — replaceResourceUri 거부 (AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH)")
        void replaceResource_published() {
            AvatarBodyResourceData d = published();

            assertThatThrownBy(() -> d.replaceResourceUri("https://gcs/new.png", ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class)
                    .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                    .isEqualTo(AvatarException.AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH.getErrorCode());
        }

        @Test
        @DisplayName("RETIRED — replaceIconUri 거부 (AVATAR_RESOURCE_RETIRED 우선)")
        void replaceIcon_retired() {
            AvatarBodyResourceData d = published();
            d.retire(ADMIN_ID);

            assertThatThrownBy(() -> d.replaceIconUri("https://gcs/new-icon.png", ADMIN_ID))
                    .isInstanceOf(AbstractHTTPException.class)
                    .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                    .isEqualTo(AvatarException.AVATAR_RESOURCE_RETIRED.getErrorCode());
        }

        @Test
        @DisplayName("DRAFT — replaceIconUri 허용")
        void replaceIcon_draft() {
            AvatarBodyResourceData d = draft();

            d.replaceIconUri("https://gcs/new-icon.png", ADMIN_ID);

            assertThat(d.getIconUri()).isEqualTo("https://gcs/new-icon.png");
        }
    }
}
