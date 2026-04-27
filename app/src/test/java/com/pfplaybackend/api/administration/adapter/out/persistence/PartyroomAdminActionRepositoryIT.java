package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PartyroomAdminActionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAdminActionRepository repository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    private Long superAdminId;

    @BeforeEach
    void seedAdmin() {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(900L), "audit-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(900L));
        this.superAdminId = superAdmin.getAdministratorId();
    }

    @Test
    @DisplayName("save → findById round-trip + metadata JSON 직렬화 확인")
    void roundTrip() {
        PartyroomAdminActionData saved = repository.save(PartyroomAdminActionData.of(
                superAdminId,
                PartyroomAdminActionType.SET_FEATURED,
                AdminActionTargetType.PARTYROOM,
                42L, 42L,
                null,
                JsonMetadata.of(Map.of("old_flag", "NORMAL", "new_flag", "FEATURED")),
                LocalDateTime.now()
        ));

        PartyroomAdminActionData reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getActionType()).isEqualTo(PartyroomAdminActionType.SET_FEATURED);
        assertThat(reloaded.getTargetType()).isEqualTo(AdminActionTargetType.PARTYROOM);
        assertThat(reloaded.getMetadata().data())
                .containsEntry("old_flag", "NORMAL")
                .containsEntry("new_flag", "FEATURED");
    }

    @Test
    @DisplayName("metadata empty → DB NULL")
    void emptyMetadata() {
        PartyroomAdminActionData saved = repository.save(PartyroomAdminActionData.of(
                superAdminId,
                PartyroomAdminActionType.RESTORE_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                43L, 43L,
                null, null,
                LocalDateTime.now()
        ));

        PartyroomAdminActionData reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getMetadata().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("findTop10ByPartyroomIdOrderByOccurredAtDesc — 시간 역순 LIMIT 10")
    void findTop10() {
        long partyroomId = 99L;
        for (int i = 0; i < 12; i++) {
            repository.save(PartyroomAdminActionData.of(
                    superAdminId,
                    PartyroomAdminActionType.UPDATE_PARTYROOM_META,
                    AdminActionTargetType.PARTYROOM,
                    partyroomId, partyroomId,
                    "iter " + i, null,
                    LocalDateTime.now().minusMinutes(11 - i)
            ));
        }

        List<PartyroomAdminActionData> top10 = repository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(top10).hasSize(10);
        assertThat(top10.get(0).getReason()).isEqualTo("iter 11");
        assertThat(top10.get(9).getReason()).isEqualTo("iter 2");
    }
}
