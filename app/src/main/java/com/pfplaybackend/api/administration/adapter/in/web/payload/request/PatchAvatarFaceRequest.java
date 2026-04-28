package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarFaceCommand;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Face 리소스 부분 수정 요청. Face는 메타데이터가 없어 multipart의 파일 슬롯만 의미 있음.
 */
@NoArgsConstructor
public class PatchAvatarFaceRequest {

    public PatchAvatarFaceCommand toCommand(Long resourceId,
                                            MultipartFile faceImage,
                                            MultipartFile iconImage,
                                            Long administratorId) throws IOException {
        return new PatchAvatarFaceCommand(
                resourceId,
                faceImage == null || faceImage.isEmpty() ? null : faceImage.getBytes(),
                faceImage == null || faceImage.isEmpty() ? null : faceImage.getContentType(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getBytes(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getContentType(),
                administratorId
        );
    }
}
