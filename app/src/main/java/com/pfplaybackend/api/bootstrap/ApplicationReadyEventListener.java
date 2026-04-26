package com.pfplaybackend.api.bootstrap;

import com.pfplaybackend.api.administration.application.service.SuperAdminSeedService;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.PartyroomCommandService;
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

    // V5 seeds the super-admin at user_id=1 (administrator_id=1, member_id=1).
    // SuperAdminSeedService replaces the placeholder credentials at boot.
    private static final UserId SUPER_ADMIN_USER_ID = new UserId(1L);

    private final Environment environment;
    private final SuperAdminSeedService superAdminSeedService;
    private final TemporaryUserInitializeService temporaryUserInitializeService;
    private final PartyroomCommandService partyroomCommandService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        // 1) Replace V5-seeded super-admin placeholder email/hash with env values.
        //    Idempotent: no-op when placeholder already replaced.
        superAdminSeedService.finalizeSuperAdminCredentials();

        // 2) Initialize main stage with the V5-seeded super-admin as host.
        //    The Member row (member_id=1, user_account_id=1, FM tier) is also
        //    seeded by V5, so PartyroomCommandService.initializeMainStage works.
        partyroomCommandService.initializeMainStage(SUPER_ADMIN_USER_ID);

        // 3) Local-only test fixtures (temporary users for development).
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            temporaryUserInitializeService.addTemporaryUsers();
        }
    }
}
