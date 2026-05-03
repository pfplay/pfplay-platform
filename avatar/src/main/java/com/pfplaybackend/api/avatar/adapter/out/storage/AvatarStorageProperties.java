package com.pfplaybackend.api.avatar.adapter.out.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * GCS 업로드 어댑터 설정. Spec §6.I-6.
 *
 * <p>{@code service-account-path}가 비어 있으면 ApplicationDefaultCredentials로 폴백한다
 * (GCE/GKE 환경 배포 시 메타데이터 서버 사용).
 */
@Configuration
@ConfigurationProperties(prefix = "app.avatar.storage")
@Getter
@Setter
public class AvatarStorageProperties {

    /** GCS 버킷 이름. 기존 Firebase Storage 버킷 재사용. */
    private String bucket;

    /** 서비스 계정 JSON 키 파일 경로. 비어 있으면 ADC 사용. */
    private String serviceAccountPath;

    /** 공개 URL 접두사. 기본 {@code https://storage.googleapis.com}. */
    private String baseUrlPrefix = "https://storage.googleapis.com";

    /** 단일 파일 최대 크기 (바이트). 기본 2 MiB — Spec §6.I-8. */
    private long maxFileSizeBytes = 2L * 1024 * 1024;
}
