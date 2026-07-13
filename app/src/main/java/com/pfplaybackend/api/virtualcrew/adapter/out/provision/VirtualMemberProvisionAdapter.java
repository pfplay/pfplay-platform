package com.pfplaybackend.api.virtualcrew.adapter.out.provision;

import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualMemberProvisionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link VirtualMemberProvisionPort} 의 유일한 구현체 — admin 레이어로 나가는 경계.
 *
 * <p>이 클래스는 virtualcrew 패키지 중 {@code ..admin..} 를 import 하도록 허용되는 유일한 곳이다.
 * 봇 계정 생성은 실유저와 동일한 {@link AdminUserService#createVirtualMember} 경로를 그대로 재사용한다.
 */
@Component
@RequiredArgsConstructor
public class VirtualMemberProvisionAdapter implements VirtualMemberProvisionPort {

    private final AdminUserService adminUserService;

    @Override
    public MemberData createVirtualMember(String nickname) {
        return adminUserService.createVirtualMember(nickname, null, null);
    }
}
