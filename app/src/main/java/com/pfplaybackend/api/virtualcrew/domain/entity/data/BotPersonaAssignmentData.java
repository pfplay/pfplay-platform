package com.pfplaybackend.api.virtualcrew.domain.entity.data;

import com.pfplaybackend.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@DynamicInsert
@DynamicUpdate
@Table(name = "bot_persona_assignment")
@Entity
public class BotPersonaAssignmentData extends BaseEntity {

    @Id
    @Comment("봇 user_account id(앱가드 참조, 무FK)")
    @Column(name = "bot_user_id", columnDefinition = "bigint unsigned")
    private Long botUserId;

    @Comment("연결된 페르소나 id")
    @Column(name = "persona_id", columnDefinition = "bigint unsigned", nullable = false)
    private Long personaId;

    protected BotPersonaAssignmentData() {
    }

    @Builder
    public BotPersonaAssignmentData(Long botUserId, Long personaId) {
        this.botUserId = botUserId;
        this.personaId = personaId;
    }

    public static BotPersonaAssignmentData create(Long botUserId, Long personaId) {
        return BotPersonaAssignmentData.builder()
                .botUserId(botUserId)
                .personaId(personaId)
                .build();
    }

    // ── Business Methods ──

    public void changePersona(Long personaId) {
        this.personaId = personaId;
    }
}
