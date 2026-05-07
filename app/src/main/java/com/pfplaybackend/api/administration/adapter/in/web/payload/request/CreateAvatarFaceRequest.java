package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarFaceCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateAvatarFaceRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9_]{3,64}$")
    private String name;

    public CreateAvatarFaceCommand toCommand(MultipartFile faceImage,
                                             MultipartFile iconImage,
                                             Long administratorId) throws IOException {
        return new CreateAvatarFaceCommand(
                name,
                faceImage.getBytes(),
                faceImage.getContentType(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getBytes(),
                iconImage == null || iconImage.isEmpty() ? null : iconImage.getContentType(),
                administratorId
        );
    }
}
