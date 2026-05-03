package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourcePublished;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceRetired;
import com.pfplaybackend.api.avatar.domain.event.AvatarResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AvatarAdminActionListenerTest {

    @Mock PartyroomAdminActionRepository repo;
    @InjectMocks AvatarAdminActionListener listener;

    @Test
    @DisplayName("AvatarResourcePublished — PUBLISH_AVATAR_RESOURCE / target=AVATAR_BODY / partyroom_id=null / metadata.resource_uri")
    void publish_inserts_audit_row() {
        AvatarResourcePublished event = new AvatarResourcePublished(
                AvatarResourceType.AVATAR_BODY, 50L, "https://gcs/b.png", 100L);

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        PartyroomAdminActionData saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(PartyroomAdminActionType.PUBLISH_AVATAR_RESOURCE);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.AVATAR_BODY);
        assertThat(saved.getTargetId()).isEqualTo(50L);
        assertThat(saved.getPartyroomId()).isNull();
        assertThat(saved.getReason()).isNull();
        assertThat(saved.getMetadata().data())
                .containsEntry("resource_uri", "https://gcs/b.png");
        assertThat(saved.getOccurredAt()).isEqualTo(event.getOccurredAt());
    }

    @Test
    @DisplayName("AvatarResourceRetired — RETIRE_AVATAR_RESOURCE / reason 포함 / target=AVATAR_FACE")
    void retire_inserts_audit_row() {
        AvatarResourceRetired event = new AvatarResourceRetired(
                AvatarResourceType.AVATAR_FACE, 7L, "이미지 오타", 100L);

        listener.on(event);

        ArgumentCaptor<PartyroomAdminActionData> captor = ArgumentCaptor.forClass(PartyroomAdminActionData.class);
        verify(repo).save(captor.capture());
        PartyroomAdminActionData saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(PartyroomAdminActionType.RETIRE_AVATAR_RESOURCE);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.AVATAR_FACE);
        assertThat(saved.getTargetId()).isEqualTo(7L);
        assertThat(saved.getPartyroomId()).isNull();
        assertThat(saved.getReason()).isEqualTo("이미지 오타");
    }

    @Test
    @DisplayName("Repo INSERT 실패 시 예외 propagate (PR 8 atomic 패턴)")
    void rethrowsOnFailure() {
        doThrow(new RuntimeException("DB 끊김")).when(repo).save(any());

        assertThatThrownBy(() -> listener.on(new AvatarResourcePublished(
                AvatarResourceType.AVATAR_BODY, 1L, "x", 1L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 끊김");
    }
}
