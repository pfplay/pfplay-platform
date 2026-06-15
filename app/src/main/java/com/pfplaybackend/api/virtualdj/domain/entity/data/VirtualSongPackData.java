package com.pfplaybackend.api.virtualdj.domain.entity.data;

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
@Table(name = "virtual_song_pack")
@Entity
public class VirtualSongPackData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigint unsigned")
    private Long id;

    @Comment("송 팩 이름 (유니크)")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Comment("송 팩 설명")
    @Column(name = "description", length = 255)
    private String description;

    protected VirtualSongPackData() {
    }

    @Builder
    public VirtualSongPackData(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public static VirtualSongPackData create(String name, String description) {
        return VirtualSongPackData.builder()
                .name(name)
                .description(description)
                .build();
    }

    // ── Business Methods ──

    public void rename(String newName) {
        this.name = newName;
    }
}
