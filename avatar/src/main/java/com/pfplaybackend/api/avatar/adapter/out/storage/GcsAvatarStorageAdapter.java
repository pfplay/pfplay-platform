package com.pfplaybackend.api.avatar.adapter.out.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Acl;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.pfplaybackend.api.avatar.application.port.out.AvatarStoragePort;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * GCS 백엔드 프록시 업로드 어댑터.
 *
 * <p>Spec §6.I-6 — 모든 어드민 업로드는 백엔드를 거쳐 {@code publicRead} 권한으로 GCS에 들어간다.
 *
 * <p>인증: {@link AvatarStorageProperties#getServiceAccountPath()}이 비어 있으면
 * ApplicationDefaultCredentials로 폴백 (GCE/GKE 메타데이터 서버, gcloud auth 등).
 */
@Component
@Slf4j
public class GcsAvatarStorageAdapter implements AvatarStoragePort {

    private final AvatarStorageProperties properties;
    private Storage storage;

    public GcsAvatarStorageAdapter(AvatarStorageProperties properties) {
        this.properties = properties;
    }

    /** Spring 초기화 후 Storage 클라이언트 생성. 실패 시 즉시 fail-fast. */
    @PostConstruct
    void init() {
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        String keyPath = properties.getServiceAccountPath();
        if (keyPath != null && !keyPath.isBlank()) {
            try (FileInputStream in = new FileInputStream(keyPath)) {
                builder.setCredentials(GoogleCredentials.fromStream(in));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "GCS service account key 로드 실패: " + keyPath, e);
            }
        }
        // else: ADC fallback — StorageOptions가 자동 감지
        this.storage = builder.build().getService();
    }

    /** 테스트 시드용 — 외부에서 mock Storage 주입. */
    public void setStorageForTest(Storage storage) {
        this.storage = storage;
    }

    @Override
    public String upload(String category, String filename, String contentType, byte[] bytes) {
        String objectName = category + "/" + filename;
        BlobInfo info = BlobInfo.newBuilder(properties.getBucket(), objectName)
                .setContentType(contentType)
                // 기존 시드 URI가 모두 publicRead — 동일 정책 유지 (Spec §6.I-6)
                .setAcl(List.of(Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER)))
                .build();
        try {
            storage.create(info, bytes);
        } catch (StorageException e) {
            log.error("[GcsAvatarStorageAdapter] upload 실패. bucket={}, object={}",
                    properties.getBucket(), objectName, e);
            throw ExceptionCreator.create(AvatarException.AVATAR_STORAGE_UPLOAD_FAILED);
        }
        return properties.getBaseUrlPrefix() + "/" + properties.getBucket() + "/" + objectName;
    }

    @Override
    public void deleteByPublicUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return;
        }
        String prefix = properties.getBaseUrlPrefix() + "/" + properties.getBucket() + "/";
        if (!publicUrl.startsWith(prefix)) {
            log.warn("[GcsAvatarStorageAdapter] 외부 URL은 삭제하지 않음: {}", publicUrl);
            return;
        }
        String encodedObject = publicUrl.substring(prefix.length());
        String objectName = URLDecoder.decode(encodedObject, StandardCharsets.UTF_8);
        try {
            boolean removed = storage.delete(BlobId.of(properties.getBucket(), objectName));
            if (!removed) {
                log.warn("[GcsAvatarStorageAdapter] 삭제 대상 객체 부재: {}", objectName);
            }
        } catch (StorageException e) {
            // 스토리지 정리는 best-effort — 실패해도 비즈니스 흐름은 진행
            log.warn("[GcsAvatarStorageAdapter] delete 실패 (swallow). object={}", objectName, e);
        }
    }
}
