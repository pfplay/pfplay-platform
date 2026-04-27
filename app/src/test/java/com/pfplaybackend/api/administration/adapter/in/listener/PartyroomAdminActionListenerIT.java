package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomCommandService;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PartyroomAdminActionListenerIT extends AbstractIntegrationTest {

    @Autowired private AdminPartyroomCommandService commandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomAdminActionRepository auditRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    private Long superAdminId;
    private Long partyroomId;

    @BeforeEach
    void seed() {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(700L), "audit-listener-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(700L));
        this.superAdminId = superAdmin.getAdministratorId();

        PartyroomData p = PartyroomData.create(
                "audit-it", "intro", LinkDomain.of("link-audit"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(700L)
        );
        this.partyroomId = partyroomRepository.saveAndFlush(p).getId();
    }

    @Test
    @DisplayName("terminate → admin_action TERMINATE_PARTYROOM 1 row + status TERMINATED 동시 commit")
    void terminate_atomic_audit() {
        commandService.terminate(new PartyroomId(partyroomId), "violation", superAdminId);

        PartyroomData reloaded = partyroomRepository.findById(partyroomId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PartyroomStatus.TERMINATED);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.TERMINATE_PARTYROOM);
        assertThat(audits.get(0).getReason()).isEqualTo("violation");
        assertThat(audits.get(0).getAdministratorId()).isEqualTo(superAdminId);
    }

    @Test
    @DisplayName("setDisplayFlag → SET_FEATURED action_type + metadata old/new")
    void setDisplayFlag_metadata() {
        commandService.setDisplayFlag(new PartyroomId(partyroomId), DisplayFlag.FEATURED, superAdminId);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.SET_FEATURED);
        assertThat(audits.get(0).getMetadata().data())
                .containsEntry("old_flag", "NORMAL")
                .containsEntry("new_flag", "FEATURED");
    }

    @Test
    @DisplayName("updateMeta → UPDATE_PARTYROOM_META + metadata.changes 직렬화")
    void updateMeta_changes_metadata() {
        commandService.updateMeta(new PartyroomId(partyroomId), "New Title", null, null, superAdminId);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.UPDATE_PARTYROOM_META);
        @SuppressWarnings("unchecked")
        var changes = (java.util.Map<String, Object>) audits.get(0).getMetadata().data().get("changes");
        assertThat(changes).containsKey("title");
    }

    @Test
    @DisplayName("suspend + restore — 2 audit rows 시간 역순 노출")
    void suspend_then_restore() {
        commandService.suspend(new PartyroomId(partyroomId), "investigation", superAdminId);
        commandService.restore(new PartyroomId(partyroomId), superAdminId);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.RESTORE_PARTYROOM);
        assertThat(audits.get(1).getActionType()).isEqualTo(PartyroomAdminActionType.SUSPEND_PARTYROOM);
    }
}
