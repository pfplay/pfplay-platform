package com.pfplaybackend.api.avatar.domain.entity.data;

import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarFaceResourceDataTest {

    private static final long ADMIN_ID = 7L;

    private AvatarFaceResourceData draft() {
        return AvatarFaceResourceData.draft(
                "ava_face_test_001", "https://gcs/face.png", "https://gcs/icon.png", ADMIN_ID);
    }

    @Test
    @DisplayName("publish — DRAFT → PUBLISHED")
    void publish_ok() {
        AvatarFaceResourceData d = draft();

        d.publish(ADMIN_ID);

        assertThat(d.getLifecycleStatus()).isEqualTo(LifecycleStatus.PUBLISHED);
        assertThat(d.getUpdatedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("retire — PUBLISHED만 허용")
    void retire_publishedOnly() {
        AvatarFaceResourceData draft = draft();
        assertThatThrownBy(() -> draft.retire(ADMIN_ID))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION.getErrorCode());

        AvatarFaceResourceData pub = draft();
        pub.publish(ADMIN_ID);
        pub.retire(ADMIN_ID);
        assertThat(pub.getLifecycleStatus()).isEqualTo(LifecycleStatus.RETIRED);
    }

    @Test
    @DisplayName("replaceResourceUri — PUBLISHED 거부")
    void replaceResource_publishedRejected() {
        AvatarFaceResourceData d = draft();
        d.publish(ADMIN_ID);

        assertThatThrownBy(() -> d.replaceResourceUri("https://gcs/x.png", ADMIN_ID))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH.getErrorCode());
    }

    @Test
    @DisplayName("replaceIconUri — DRAFT에서 정상 적용")
    void replaceIcon_draft() {
        AvatarFaceResourceData d = draft();

        d.replaceIconUri("https://gcs/new-icon.png", ADMIN_ID);

        assertThat(d.getIconUri()).isEqualTo("https://gcs/new-icon.png");
        assertThat(d.getUpdatedBy()).isEqualTo(ADMIN_ID);
    }
}
