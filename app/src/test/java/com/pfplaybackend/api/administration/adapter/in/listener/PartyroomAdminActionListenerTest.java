package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenaltyReleasedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PartyroomAdminActionListenerTest {

    @Mock PartyroomAdminActionRepository repo;
    @InjectMocks PartyroomAdminActionListener listener;

    @Test
    @DisplayName("on(AdminCrewPenalizedEvent) PERMANENT: action_type=PENALIZE_CREW, target_type=CREW, metadata 매핑")
    void on_AdminCrewPenalizedEvent_inserts_audit_row() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                new PartyroomId(1L), 100L, new CrewId(10L),
                PenaltyType.PERMANENT_EXPULSION, 999L, "abuse");

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        PartyroomAdminActionData saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(PartyroomAdminActionType.PENALIZE_CREW);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.CREW);
        assertThat(saved.getTargetId()).isEqualTo(10L);
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getReason()).isEqualTo("abuse");
        assertThat(saved.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(saved.getMetadata().data())
                .containsEntry("penalty_type", "PERMANENT_EXPULSION")
                .containsEntry("crew_penalty_history_id", 999L);
    }

    @Test
    @DisplayName("on(AdminCrewPenalizedEvent) ONE_TIME: metadata에 crew_penalty_history_id 없음")
    void on_AdminCrewPenalizedEvent_one_time_omits_history_id() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                new PartyroomId(1L), 100L, new CrewId(10L),
                PenaltyType.ONE_TIME_EXPULSION, null, "warning");

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getMetadata().data())
                .containsEntry("penalty_type", "ONE_TIME_EXPULSION")
                .doesNotContainKey("crew_penalty_history_id");
    }

    @Test
    @DisplayName("on(AdminCrewPenaltyReleasedEvent): action_type=RELEASE_CREW_PENALTY, reason=null, metadata만 history_id")
    void on_AdminCrewPenaltyReleasedEvent_inserts_audit_row() {
        AdminCrewPenaltyReleasedEvent event = new AdminCrewPenaltyReleasedEvent(
                new PartyroomId(1L), 100L, new CrewId(10L), 999L);

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        PartyroomAdminActionData saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(PartyroomAdminActionType.RELEASE_CREW_PENALTY);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.CREW);
        assertThat(saved.getTargetId()).isEqualTo(10L);
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getReason()).isNull();
        assertThat(saved.getMetadata().data()).containsEntry("crew_penalty_history_id", 999L);
    }
}
