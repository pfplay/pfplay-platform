package com.pfplaybackend.api.bootstrap;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.PartyroomCommandService;
import com.pfplaybackend.api.user.application.service.initialize.AdminUserInitializeService;
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
    private final TemporaryUserInitializeService temporaryUserInitializeService;
    private final AdminUserInitializeService adminUserInitializeService;
    private final PartyroomCommandService partyroomCommandService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        // 정적 fixture (avatar 리소스)는 Flyway V3에서 시드되므로 여기서 처리하지 않는다.
        // 여기 남은 것들은 비즈니스 로직이 있는 시드: admin placeholder 유저(프로필/활동 초기화 동반),
        // 메인 스테이지 파티룸(host 지정 + crew 등록 동반), 그리고 로컬 전용 임시 유저.
        // 모두 skip-if-exists 멱등이라 모든 프로파일에서 안전하게 반복 실행된다.

        // FIXME 서비스 간 '구동 순서에 대한 의존 문제'를 해소
        UserId adminId = adminUserInitializeService.addAdminUser();
        partyroomCommandService.initializeMainStage(adminId);

        // 임시 테스트 유저는 로컬 개발 편의용 — 운영 환경에는 들어가지 않음
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            temporaryUserInitializeService.addTemporaryUsers();
        }
    }
}
