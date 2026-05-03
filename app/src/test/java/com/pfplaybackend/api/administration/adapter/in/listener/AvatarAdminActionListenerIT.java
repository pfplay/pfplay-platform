package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogCommandUseCase;
import com.pfplaybackend.api.avatar.application.port.out.AvatarStoragePort;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어드민 publish/retire 이벤트가 atomic하게 {@code partyroom_admin_action} INSERT 되는지 검증.
 * 스토리지는 mock 처리(PR 11 IT는 GCS 인증 의존 회피).
 */
@Transactional
class AvatarAdminActionListenerIT extends AbstractIntegrationTest {

    @Autowired private AvatarCatalogCommandUseCase commandUseCase;
    @Autowired private AvatarBodyResourceRepository bodyRepo;
    @Autowired private PartyroomAdminActionRepository auditRepo;
    @Autowired private AdministratorRepository administratorRepo;
    @Autowired private UserAccountRepository userAccountRepo;

    /** GCS 호출 발생을 막기 위해 publish 경로엔 storage 사용이 없지만, 컨텍스트 시작용 fake. */
    @MockBean private AvatarStoragePort avatarStoragePort;

    private Long superAdminId;
    private Long bodyId;

    @BeforeEach
    void seed() {
        userAccountRepo.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(800L), "avatar-listener-it@x", "h"));
        AdministratorData admin = administratorRepo.save(AdministratorData.createSuperAdmin(800L));
        this.superAdminId = admin.getAdministratorId();

        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "ava_body_it_001", "https://gcs/body.png", "https://gcs/icon.png",
                ObtainmentType.BASIC, 0, true, false, 0, 0, superAdminId);
        this.bodyId = bodyRepo.saveAndFlush(body).getId();
    }

    @Test
    @DisplayName("publishBody → admin_action(PUBLISH_AVATAR_RESOURCE) 1건 + body lifecycle=PUBLISHED 동시 commit")
    void publishBody_writesAuditRow() {
        commandUseCase.publishBody(bodyId, superAdminId);
        flushAndClear();

        AvatarBodyResourceData reloaded = bodyRepo.findById(bodyId).orElseThrow();
        assertThat(reloaded.getLifecycleStatus()).isEqualTo(LifecycleStatus.PUBLISHED);

        List<PartyroomAdminActionData> rows = auditRepo.findAll().stream()
                .filter(r -> r.getTargetType() == AdminActionTargetType.AVATAR_BODY
                        && r.getTargetId().equals(bodyId))
                .toList();
        assertThat(rows).hasSize(1);
        PartyroomAdminActionData row = rows.get(0);
        assertThat(row.getActionType()).isEqualTo(PartyroomAdminActionType.PUBLISH_AVATAR_RESOURCE);
        assertThat(row.getAdministratorId()).isEqualTo(superAdminId);
        assertThat(row.getPartyroomId()).isNull();
        assertThat(row.getMetadata().data())
                .containsEntry("resource_uri", "https://gcs/body.png");
    }

    @Test
    @DisplayName("retireBody → admin_action(RETIRE_AVATAR_RESOURCE, reason) 1건 + body lifecycle=RETIRED")
    void retireBody_writesAuditRow() {
        // PUBLISHED로 먼저 전이
        commandUseCase.publishBody(bodyId, superAdminId);
        flushAndClear();

        commandUseCase.retireBody(bodyId, "이미지 오타", superAdminId);
        flushAndClear();

        AvatarBodyResourceData reloaded = bodyRepo.findById(bodyId).orElseThrow();
        assertThat(reloaded.getLifecycleStatus()).isEqualTo(LifecycleStatus.RETIRED);

        List<PartyroomAdminActionData> retireRows = auditRepo.findAll().stream()
                .filter(r -> r.getActionType() == PartyroomAdminActionType.RETIRE_AVATAR_RESOURCE
                        && r.getTargetId().equals(bodyId))
                .toList();
        assertThat(retireRows).hasSize(1);
        assertThat(retireRows.get(0).getReason()).isEqualTo("이미지 오타");
    }
}
