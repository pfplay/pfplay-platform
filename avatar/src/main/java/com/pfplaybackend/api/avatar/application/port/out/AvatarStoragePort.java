package com.pfplaybackend.api.avatar.application.port.out;

/**
 * 아바타 리소스 파일 스토리지 포트. 어드민 업로드/삭제 백엔드 프록시 (Spec §6.I-6).
 *
 * <p>구현체: {@code GcsAvatarStorageAdapter}. 테스트 환경에서는 in-memory fake 가능.
 */
public interface AvatarStoragePort {

    /**
     * 카테고리 폴더 아래에 파일 업로드.
     *
     * @param category    {@code "ava_body"} | {@code "ava_face"} | {@code "ava_icon"} 등 폴더 이름
     * @param filename    최종 객체 파일명 (호출자가 생성. 예: {@code 20260428_a1b2c3.png})
     * @param contentType MIME 타입 ({@code image/png}, {@code image/jpeg})
     * @param bytes       파일 본문
     * @return 공개 다운로드 URL
     */
    String upload(String category, String filename, String contentType, byte[] bytes);

    /**
     * 공개 URL을 받아 GCS 객체를 삭제. 실패 시 swallow + 로그(스토리지 정리는 best-effort).
     *
     * @param publicUrl {@link #upload}이 반환한 그대로의 URL
     */
    void deleteByPublicUrl(String publicUrl);
}
