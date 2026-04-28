package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.domain.enums.ProfileChangeType;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserActivityLogListenerTest {

    @Mock UserActivityLogRepository repository;
    UserActivityLogListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserActivityLogListener(repository);
    }

    @Test
    @DisplayName("MemberRegisteredEvent → SIGNED_UP row INSERT (provider metadata)")
    void on_MemberRegisteredEvent_inserts_SIGNED_UP_row() {
        UserId userId = UserId.create(100L);
        MemberRegisteredEvent event = new MemberRegisteredEvent(userId, "user@example.com", ProviderType.GOOGLE);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(100L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.SIGNED_UP.name());
        assertThat(saved.getPartyroomId()).isNull();
        assertThat(saved.getOccurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(saved.getMetadata().data()).containsEntry("provider", "GOOGLE");
    }

    @Test
    @DisplayName("UserProfileChangedEvent → PROFILE_UPDATED row INSERT (change_type metadata)")
    void on_UserProfileChangedEvent_inserts_PROFILE_UPDATED_row() {
        UserId userId = UserId.create(100L);
        UserProfileChangedEvent event = new UserProfileChangedEvent(userId, ProfileChangeType.AVATAR);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PROFILE_UPDATED.name());
        assertThat(saved.getMetadata().data()).containsEntry("change_type", "AVATAR");
    }

    @Test
    @DisplayName("AdminCrewPenalizedEvent → PENALIZED_IN_PARTYROOM row INSERT (by=ADMIN)")
    void on_AdminCrewPenalizedEvent_inserts_PENALIZED_IN_PARTYROOM_row() {
        AdminCrewPenalizedEvent event = new AdminCrewPenalizedEvent(
                new PartyroomId(1L), 100L, new CrewId(50L),
                999L, PenaltyType.PERMANENT_EXPULSION, 200L, "abuse");

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(999L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PENALIZED_IN_PARTYROOM.name());
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getMetadata().data())
                .containsEntry("penalty_type", "PERMANENT_EXPULSION")
                .containsEntry("by", "ADMIN")
                .containsEntry("by_administrator_id", 100L);
    }

    @Test
    @DisplayName("CrewPenalizedEvent → PENALIZED_IN_PARTYROOM row INSERT (by=CREW)")
    void on_CrewPenalizedEvent_inserts_PENALIZED_IN_PARTYROOM_row() {
        CrewPenalizedEvent event = new CrewPenalizedEvent(
                new PartyroomId(1L), new CrewId(10L), new CrewId(50L),
                999L, "abuse", PenaltyType.PERMANENT_EXPULSION);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(999L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PENALIZED_IN_PARTYROOM.name());
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getMetadata().data())
                .containsEntry("penalty_type", "PERMANENT_EXPULSION")
                .containsEntry("by", "CREW");
        assertThat(saved.getMetadata().data()).doesNotContainKey("by_administrator_id");
    }

    @Test
    @DisplayName("PartyroomCreatedEvent → PARTYROOM_CREATED row INSERT (host metadata)")
    void on_PartyroomCreatedEvent_inserts_PARTYROOM_CREATED_row() {
        PartyroomCreatedEvent event = new PartyroomCreatedEvent(
                new PartyroomId(1L), 100L, StageType.GENERAL);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(100L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PARTYROOM_CREATED.name());
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getMetadata().data()).containsEntry("stage_type", "GENERAL");
    }

    @Test
    @DisplayName("repository.save 실패해도 throw 없이 swallow (drop-가능)")
    void on_save_failure_swallows() {
        doThrow(new RuntimeException("db down")).when(repository).save(any());

        UserId userId = UserId.create(100L);
        MemberRegisteredEvent event = new MemberRegisteredEvent(userId, "user@example.com", ProviderType.LOCAL);

        listener.on(event);   // throw 안 함

        verify(repository).save(any());
    }
}
