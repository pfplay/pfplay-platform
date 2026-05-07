package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarBodyCommand;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Body 리소스 수정 요청. 메타데이터 6필드는 항상 송신, 이미지는 선택. Spec §6.I-3.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PatchAvatarBodyRequest {

    @NotNull
    private ObtainmentType obtainableType;

    @PositiveOrZero
    private int obtainableScore;

    private boolean combinable;

    private boolean defaultSetting;

    private int combinePositionX;

    private int combinePositionY;

    public PatchAvatarBodyCommand toCommand(Long resourceId,
                                            MultipartFile bodyImage,
                                            MultipartFile iconImage,
                                            Long administratorId) throws IOException {
        return new PatchAvatarBodyCommand(
                resourceId,
                obtainableType,
                obtainableScore,
                combinable,
                defaultSetting,
                combinePositionX,
                combinePositionY,
                bodyImage == null || bodyImage.isEmpty() ? null : bodyImage.getBytes(),
                bodyImage == null || bodyImage.isEmpty() ? null : bodyImage.getContentType(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getBytes(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getContentType(),
                administratorId
        );
    }
}
