package com.pfplaybackend.api.avatar.adapter.out.storage;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcsAvatarStorageAdapterTest {

    private static final String BUCKET = "test-bucket";
    private static final String PREFIX = "https://storage.googleapis.com";

    private Storage storage;
    private GcsAvatarStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        AvatarStorageProperties props = new AvatarStorageProperties();
        props.setBucket(BUCKET);
        props.setBaseUrlPrefix(PREFIX);
        adapter = new GcsAvatarStorageAdapter(props);
        storage = mock(Storage.class);
        adapter.setStorageForTest(storage);
    }

    @Test
    @DisplayName("upload — Storage.create 호출 + 공개 URL 반환")
    void upload_returnsPublicUrl() {
        byte[] bytes = new byte[]{1, 2, 3};

        String url = adapter.upload("ava_body", "20260428_a1b2.png", "image/png", bytes);

        ArgumentCaptor<BlobInfo> infoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(infoCaptor.capture(), any(byte[].class));
        BlobInfo info = infoCaptor.getValue();
        assertThat(info.getBucket()).isEqualTo(BUCKET);
        assertThat(info.getName()).isEqualTo("ava_body/20260428_a1b2.png");
        assertThat(info.getContentType()).isEqualTo("image/png");
        assertThat(url).isEqualTo("https://storage.googleapis.com/test-bucket/ava_body/20260428_a1b2.png");
    }

    @Test
    @DisplayName("upload — Storage 예외 시 AVATAR_STORAGE_UPLOAD_FAILED")
    void upload_translatesStorageException() {
        when(storage.create(any(BlobInfo.class), any(byte[].class)))
                .thenThrow(new StorageException(503, "service unavailable"));

        assertThatThrownBy(() -> adapter.upload("ava_body", "x.png", "image/png", new byte[]{1}))
                .isInstanceOf(AbstractHTTPException.class)
                .extracting(t -> ((AbstractHTTPException) t).getErrorCode())
                .isEqualTo(AvatarException.AVATAR_STORAGE_UPLOAD_FAILED.getErrorCode());
    }

    @Test
    @DisplayName("deleteByPublicUrl — 우리 prefix URL → BlobId.of(bucket, object)로 delete")
    void delete_parsesObjectFromUrl() {
        String url = "https://storage.googleapis.com/test-bucket/ava_face/20260428_x.png";

        adapter.deleteByPublicUrl(url);

        verify(storage).delete(BlobId.of(BUCKET, "ava_face/20260428_x.png"));
    }

    @Test
    @DisplayName("deleteByPublicUrl — 외부 URL은 호출 자체를 안 한다 (방어)")
    void delete_skipsForeignUrl() {
        adapter.deleteByPublicUrl("https://example.com/some.png");
        verify(storage, never()).delete(any(BlobId.class));
    }

    @Test
    @DisplayName("deleteByPublicUrl — null/blank 안전")
    void delete_nullSafe() {
        adapter.deleteByPublicUrl(null);
        adapter.deleteByPublicUrl("");
        verify(storage, never()).delete(any(BlobId.class));
    }

    @Test
    @DisplayName("deleteByPublicUrl — Storage 예외 swallow")
    void delete_swallowsStorageException() {
        when(storage.delete(any(BlobId.class)))
                .thenThrow(new StorageException(500, "boom"));

        // 예외가 propagate되지 않아야 함
        adapter.deleteByPublicUrl("https://storage.googleapis.com/test-bucket/ava_body/x.png");
    }
}
