package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.auth.domain.event.UserAccountSignedInEvent;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.domain.enums.ProfileChangeType;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent;
import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
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
    @DisplayName("UserAccountSignedInEvent (USER) → SIGNED_IN row INSERT")
    void on_UserAccountSignedInEvent_user_inserts_SIGNED_IN_row() {
        UserAccountSignedInEvent event = new UserAccountSignedInEvent(
                100L, ProviderType.GOOGLE, UserAccountSignedInEvent.ActorType.USER);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(100L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.SIGNED_IN.name());
        assertThat(saved.getPartyroomId()).isNull();
        assertThat(saved.getMetadata().data())
                .containsEntry("provider", "GOOGLE")
                .containsEntry("actor_type", "USER");
    }

    @Test
    @DisplayName("UserAccountSignedInEvent (ADMINISTRATOR) → SIGNED_IN row INSERT")
    void on_UserAccountSignedInEvent_admin_inserts_SIGNED_IN_row() {
        UserAccountSignedInEvent event = new UserAccountSignedInEvent(
                100L, ProviderType.LOCAL, UserAccountSignedInEvent.ActorType.ADMINISTRATOR);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getMetadata().data()).containsEntry("actor_type", "ADMINISTRATOR");
    }

    @Test
    @DisplayName("CrewAccessedEvent ENTER → PARTYROOM_ENTERED row INSERT")
    void on_CrewAccessedEvent_enter_inserts_PARTYROOM_ENTERED_row() {
        UserId userId = UserId.create(100L);
        CrewAccessedEvent event = new CrewAccessedEvent(
                new PartyroomId(1L), new CrewId(50L), userId, AccessType.ENTER);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        UserActivityLogData saved = cap.getValue();

        assertThat(saved.getUserAccountId()).isEqualTo(100L);
        assertThat(saved.getEventType()).isEqualTo(UserActivityEventType.PARTYROOM_ENTERED.name());
        assertThat(saved.getPartyroomId()).isEqualTo(1L);
        // metadata 단순화 — CrewAccessedEvent에 stage_type/duration_sec 부재.
        // listener는 JsonMetadata.empty() 사용 (converter가 빈 map → SQL NULL 직렬화).
        assertThat(saved.getMetadata().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("CrewAccessedEvent EXIT → PARTYROOM_EXITED row INSERT")
    void on_CrewAccessedEvent_exit_inserts_PARTYROOM_EXITED_row() {
        UserId userId = UserId.create(100L);
        CrewAccessedEvent event = new CrewAccessedEvent(
                new PartyroomId(1L), new CrewId(50L), userId, AccessType.EXIT);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(UserActivityEventType.PARTYROOM_EXITED.name());
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

    @Test
    @DisplayName("MemberTierChangedEvent → TIER_CHANGED + ADMIN_ACTED_ON 2 row INSERT")
    void on_MemberTierChangedEvent_inserts_two_rows() {
        MemberTierChangedEvent event = new MemberTierChangedEvent(
                100L, 50L, AuthorityTier.AM, AuthorityTier.FM, 999L);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository, times(2)).save(cap.capture());
        var rows = cap.getAllValues();

        // Row 1: TIER_CHANGED (insertion order — log_id ASC)
        assertThat(rows.get(0).getEventType()).isEqualTo(UserActivityEventType.TIER_CHANGED.name());
        assertThat(rows.get(0).getUserAccountId()).isEqualTo(100L);
        assertThat(rows.get(0).getPartyroomId()).isNull();
        assertThat(rows.get(0).getMetadata().data())
                .containsEntry("old_tier", "AM")
                .containsEntry("new_tier", "FM")
                .containsEntry("by_administrator_id", 999L);
        assertThat(rows.get(0).getOccurredAt()).isEqualTo(event.getOccurredAt());

        // Row 2: ADMIN_ACTED_ON
        assertThat(rows.get(1).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name());
        assertThat(rows.get(1).getUserAccountId()).isEqualTo(100L);
        assertThat(rows.get(1).getMetadata().data())
                .containsEntry("action_type", "TIER_CHANGED")
                .containsEntry("by_administrator_id", 999L);
        assertThat(rows.get(1).getOccurredAt()).isEqualTo(event.getOccurredAt());
    }

    @Test
    @DisplayName("UserAccountWithdrawnEvent → WITHDREW + ADMIN_ACTED_ON 2 row INSERT")
    void on_UserAccountWithdrawnEvent_inserts_two_rows() {
        UserAccountWithdrawnEvent event = new UserAccountWithdrawnEvent(
                100L, "withdrawn-100@withdrawn.local", 999L);

        listener.on(event);

        ArgumentCaptor<UserActivityLogData> cap = ArgumentCaptor.forClass(UserActivityLogData.class);
        verify(repository, times(2)).save(cap.capture());
        var rows = cap.getAllValues();

        // Row 1: WITHDREW
        assertThat(rows.get(0).getEventType()).isEqualTo(UserActivityEventType.WITHDREW.name());
        assertThat(rows.get(0).getUserAccountId()).isEqualTo(100L);
        assertThat(rows.get(0).getPartyroomId()).isNull();
        assertThat(rows.get(0).getMetadata().data())
                .containsEntry("by_administrator_id", 999L);

        // Row 2: ADMIN_ACTED_ON
        assertThat(rows.get(1).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name());
        assertThat(rows.get(1).getUserAccountId()).isEqualTo(100L);
        assertThat(rows.get(1).getMetadata().data())
                .containsEntry("action_type", "WITHDRAW")
                .containsEntry("by_administrator_id", 999L);
    }
}
