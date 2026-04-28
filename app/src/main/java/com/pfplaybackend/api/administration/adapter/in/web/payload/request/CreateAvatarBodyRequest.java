package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarBodyCommand;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 새 body 리소스 생성 요청 (multipart). Spec §6.I-2.
 *
 * <p>multipart는 record/사용자 생성자가 어색해 plain class + setter 사용.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateAvatarBodyRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9_]{3,64}$")
    private String name;

    @NotNull
    private ObtainmentType obtainableType;

    @PositiveOrZero
    private int obtainableScore;

    private boolean combinable;

    private boolean defaultSetting;

    private int combinePositionX;

    private int combinePositionY;

    public CreateAvatarBodyCommand toCommand(MultipartFile bodyImage,
                                             MultipartFile iconImage,
                                             Long administratorId) throws IOException {
        return new CreateAvatarBodyCommand(
                name,
                bodyImage.getBytes(),
                bodyImage.getContentType(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getBytes(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getContentType(),
                obtainableType,
                obtainableScore,
                combinable,
                defaultSetting,
                combinePositionX,
                combinePositionY,
                administratorId
        );
    }
}
