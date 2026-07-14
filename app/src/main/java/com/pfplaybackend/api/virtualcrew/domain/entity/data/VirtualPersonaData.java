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
@Table(name = "virtual_persona")
@Entity
public class VirtualPersonaData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Comment("어드민 식별용 페르소나 이름")
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Comment("LLM system 지시문(성격/톤/장르 성향)")
    @Column(name = "instruction", nullable = false, columnDefinition = "TEXT")
    private String instruction;

    @Comment("비활성 시 신규 매핑 불가(기존 보존)")
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected VirtualPersonaData() {
    }

    @Builder
    public VirtualPersonaData(String name, String instruction, boolean active) {
        this.name = name;
        this.instruction = instruction;
        this.active = active;
    }

    public static VirtualPersonaData create(String name, String instruction) {
        return VirtualPersonaData.builder()
                .name(name)
                .instruction(instruction)
                .active(true)
                .build();
    }

    // ── Business Methods ──

    public void update(String name, String instruction) {
        this.name = name;
        this.instruction = instruction;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
