package com.pfplaybackend.api.user.adapter.in.web;

import com.pfplaybackend.api.user.adapter.in.web.payload.request.UpdateAvatarRequest;
import com.pfplaybackend.api.user.application.dto.command.SetAvatarCommand;
import com.pfplaybackend.api.user.application.service.UserAvatarCommandService;
import com.pfplaybackend.api.user.application.validation.AvatarRequestValidator;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import com.pfplaybackend.api.user.domain.exception.UserAvatarException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile API")
@RequestMapping("/api/v1/users")
@RestController
@RequiredArgsConstructor
public class UserAvatarCommandController {

    private final UserAvatarCommandService userAvatarCommandService;
    private final AvatarRequestValidator avatarRequestValidator;

    @Operation(summary = "아바타 변경", description = "현재 인증된 회원의 아바타를 변경합니다. 바디와 표정 조합을 설정할 수 있습니다. 회원만 사용 가능합니다.")
    @ApiResponse(responseCode = "204", description = "아바타 변경 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({UserAvatarException.class})
    @PreAuthorize("hasRole('MEMBER')")
    @PutMapping("/me/profile/avatar")
    public ResponseEntity<Void> setMyAvatar(@Valid @RequestBody UpdateAvatarRequest request) {
        SetAvatarCommand command = toCommand(request);
        avatarRequestValidator.validate(command);
        userAvatarCommandService.setUserAvatar(command);
        return ResponseEntity.noContent().build();
    }

    private SetAvatarCommand toCommand(UpdateAvatarRequest request) {
        SetAvatarCommand.AvatarFaceSpec faceSpec = null;
        if (request.getFace() != null) {
            faceSpec = new SetAvatarCommand.AvatarFaceSpec(
                    request.getFace().getUri(),
                    request.getFace().getSourceType(),
                    new SetAvatarCommand.AvatarTransformSpec(
                            request.getFace().getTransform().getOffsetX(),
                            request.getFace().getTransform().getOffsetY(),
                            request.getFace().getTransform().getScale()
                    )
            );
        }
        return new SetAvatarCommand(
                request.getAvatarCompositionType(),
                new SetAvatarCommand.AvatarBodySpec(request.getBody().getUri()),
                faceSpec
        );
    }
}
