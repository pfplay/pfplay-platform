package com.pfplaybackend.api.virtualdj.adapter.out.provision;

import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.port.BotAvatarApplyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link BotAvatarApplyPort} 의 유일한 구현체 — admin 레이어로 나가는 경계.
 *
 * <p>{@link VirtualMemberProvisionAdapter} 와 함께 virtualdj 패키지 중 {@code ..admin..} 를
 * import 하도록 허용되는 곳이다. 합성/아이콘의 실제 결정은 admin 의 기존
 * {@link AdminUserService#updateVirtualMemberAvatar} 로직을 그대로 재사용한다.
 */
@Component
@RequiredArgsConstructor
public class BotAvatarApplyAdapter implements BotAvatarApplyPort {

    private final AdminUserService adminUserService;

    @Override
    public void apply(UserId botUserId, AvatarBodyUri bodyUri, AvatarFaceUri faceUri) {
        adminUserService.updateVirtualMemberAvatar(botUserId, bodyUri, faceUri);
    }
}
