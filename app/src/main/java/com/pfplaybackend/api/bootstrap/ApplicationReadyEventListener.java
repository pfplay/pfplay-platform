package com.pfplaybackend.api.bootstrap;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.PartyroomCommandService;
import com.pfplaybackend.api.user.application.service.initialize.AdminUserInitializeService;
import com.pfplaybackend.api.user.application.service.initialize.AvatarResourceInitializeService;
import com.pfplaybackend.api.user.application.service.initialize.TemporaryUserInitializeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.initialization.enabled", havingValue = "true", matchIfMissing = true)
public class ApplicationReadyEventListener {

    private final Environment environment;
    private final AvatarResourceInitializeService avatarResourceInitializeService;
    private final TemporaryUserInitializeService temporaryUserInitializeService;
    private final AdminUserInitializeService adminUserInitializeService;
    private final PartyroomCommandService partyroomCommandService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        // 시드 데이터는 schema가 fresh인 환경(ddl-auto=create: local, dev)에서만 의미가 있음.
        // staging/prod(ddl-auto=validate)는 기존 데이터와 충돌하므로 스킵.
        if (!environment.acceptsProfiles(Profiles.of("local", "dev"))) {
            return;
        }

        avatarResourceInitializeService.addAvatarBodies();
        avatarResourceInitializeService.addAvatarFaces();
        avatarResourceInitializeService.addAvatarIcons();

        // FIXME 서비스 간 '구동 순서에 대한 의존 문제'를 해소
        UserId adminId = adminUserInitializeService.addAdminUser();
        partyroomCommandService.initializeMainStage(adminId);

        if (environment.acceptsProfiles(Profiles.of("local"))) {
            temporaryUserInitializeService.addTemporaryUsers();
        }
    }
}
