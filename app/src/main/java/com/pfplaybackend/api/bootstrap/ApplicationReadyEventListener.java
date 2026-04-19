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
        // 모든 프로파일에서 실행. 각 initializer는 skip-if-exists로 멱등하므로
        // 기존 데이터와 충돌 없이 빈 환경(예: 새 prod DB 초기화)에서만 실제 INSERT가 발생한다.
        avatarResourceInitializeService.addAvatarBodies();
        avatarResourceInitializeService.addAvatarFaces();
        avatarResourceInitializeService.addAvatarIcons();

        // FIXME 서비스 간 '구동 순서에 대한 의존 문제'를 해소
        UserId adminId = adminUserInitializeService.addAdminUser();
        partyroomCommandService.initializeMainStage(adminId);

        // 임시 테스트 유저는 로컬 개발 편의용 데이터 — 운영 환경에는 들어가지 않음
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            temporaryUserInitializeService.addTemporaryUsers();
        }
    }
}
