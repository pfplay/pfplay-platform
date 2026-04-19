
    create table avatar_body_resource (
        combine_positionx integer not null,
        combine_positiony integer not null,
        is_combinable bit not null,
        is_default_setting bit not null,
        obtainable_score integer not null,
        id bigint not null auto_increment,
        name varchar(255) not null,
        resource_uri varchar(255) not null,
        obtainable_type enum ('BASIC','DJ_PNT','REF_LINK','ROOM_ACT') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table avatar_face_resource (
        id bigint not null auto_increment,
        name varchar(255) not null,
        resource_uri varchar(255) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table avatar_icon_resource (
        pair_type tinyint not null check (pair_type between 0 and 1),
        id bigint not null auto_increment,
        name varchar(255) not null,
        resource_uri varchar(255) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table crew (
        grade_type tinyint check (grade_type between 0 and 4),
        is_active bit,
        is_banned bit not null,
        created_at datetime default current_timestamp,
        crew_id bigint not null auto_increment,
        entered_at datetime(6) not null,
        exited_at datetime(6),
        partyroom_id bigint not null,
        updated_at datetime default current_timestamp on update current_timestamp,
        user_id bigint,
        primary key (crew_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table crew_block_history (
        is_unblocked bit not null,
        block_date datetime(6) not null,
        blocked_crew_id bigint,
        blocked_user_id bigint,
        blocker_crew_id bigint,
        created_at datetime default current_timestamp,
        id bigint not null auto_increment,
        unblock_date datetime(6),
        updated_at datetime default current_timestamp on update current_timestamp,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table crew_grade_history (
        adjusted_crew_id bigint,
        adjuster_crew_id bigint,
        adjustment_date datetime(6) not null,
        created_at datetime default current_timestamp,
        id bigint not null auto_increment,
        partyroom_id bigint,
        updated_at datetime default current_timestamp on update current_timestamp,
        new_grade enum ('HOST','COMMUNITY_MANAGER','MODERATOR','CLUBBER','LISTENER'),
        previous_grade enum ('HOST','COMMUNITY_MANAGER','MODERATOR','CLUBBER','LISTENER'),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table crew_penalty_history (
        is_released bit not null,
        created_at datetime default current_timestamp,
        id bigint not null auto_increment,
        partyroom_id bigint,
        penalty_date datetime(6) not null,
        punished_crew_id bigint,
        punisher_crew_id bigint,
        release_date datetime(6),
        released_by_crew_id bigint,
        updated_at datetime default current_timestamp on update current_timestamp,
        penalty_reason varchar(255),
        penalty_type enum ('CHAT_MESSAGE_REMOVAL','CHAT_BAN_30_SECONDS','ONE_TIME_EXPULSION','PERMANENT_EXPULSION'),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table dj (
        order_number integer not null,
        created_at datetime default current_timestamp,
        crew_id bigint,
        dj_id bigint not null auto_increment,
        partyroom_id bigint not null,
        playlist_id bigint,
        updated_at datetime default current_timestamp on update current_timestamp,
        primary key (dj_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table dj_queue (
        is_closed bit not null,
        created_at datetime default current_timestamp,
        partyroom_id bigint not null,
        updated_at datetime default current_timestamp on update current_timestamp,
        primary key (partyroom_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table guest (
        user_id bigint not null,
        agent varchar(255),
        primary key (user_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table member (
        provider_type tinyint not null check (provider_type between 0 and 2),
        user_id bigint not null,
        email varchar(255) not null,
        primary key (user_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table partyroom (
        is_terminated bit not null,
        playback_time_limit integer,
        created_at datetime default current_timestamp,
        host_id bigint,
        partyroom_id bigint not null auto_increment,
        updated_at datetime default current_timestamp on update current_timestamp,
        introduction varchar(255),
        link_domain varchar(255),
        notice_content varchar(255),
        title varchar(255),
        stage_type enum ('MAIN','GENERAL'),
        primary key (partyroom_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table partyroom_playback (
        is_activated bit not null,
        created_at datetime default current_timestamp,
        current_dj_crew_id bigint,
        current_playback_id bigint,
        partyroom_id bigint not null,
        updated_at datetime default current_timestamp on update current_timestamp,
        primary key (partyroom_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table playback (
        created_at datetime default current_timestamp,
        end_time bigint,
        id bigint not null auto_increment,
        partyroom_id bigint,
        updated_at datetime default current_timestamp on update current_timestamp,
        user_id bigint,
        duration varchar(255),
        link_id varchar(255),
        name varchar(255),
        thumbnail_image varchar(255),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table playback_aggregation (
        dislike_count integer not null,
        grab_count integer not null,
        like_count integer not null,
        created_at datetime default current_timestamp,
        playback_id bigint not null,
        updated_at datetime default current_timestamp on update current_timestamp,
        primary key (playback_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table playback_reaction_history (
        disliked bit,
        grabbed bit,
        liked bit,
        created_at datetime default current_timestamp,
        id bigint not null auto_increment,
        playback_id bigint,
        updated_at datetime default current_timestamp on update current_timestamp,
        user_id bigint,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table playlist (
        order_number integer unsigned comment '플레이리스트 순서',
        created_at datetime default current_timestamp,
        id bigint unsigned not null auto_increment,
        owner_id bigint,
        updated_at datetime default current_timestamp on update current_timestamp,
        name varchar(255) comment '플레이리스트 이름',
        type enum ('GRABLIST','PLAYLIST') comment '플레이리스트 타입',
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table track (
        order_number integer unsigned comment '플레이리스트의 곡 순서',
        created_at datetime default current_timestamp,
        id bigint unsigned not null auto_increment,
        playlist_id bigint not null,
        updated_at datetime default current_timestamp on update current_timestamp,
        duration varchar(255) comment '곡 총 재생 시간',
        link_id varchar(255) comment '링크 식별자',
        name varchar(255) comment '곡 이름',
        thumbnail_image varchar(255) comment '썸네일 이미지 url',
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table user_account (
        is_profile_updated bit not null,
        created_at datetime default current_timestamp,
        profile_id bigint unsigned,
        updated_at datetime default current_timestamp on update current_timestamp,
        user_id bigint not null,
        user_type varchar(31) not null,
        authority_tier enum ('FM','AM','GT') not null,
        primary key (user_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table user_activity (
        score integer unsigned,
        created_at datetime default current_timestamp,
        id bigint unsigned not null auto_increment,
        updated_at datetime default current_timestamp on update current_timestamp,
        user_id bigint,
        activity_type enum ('DJ_PNT','REF_LINK','ROOM_ACT'),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table user_profile (
        combine_positionx integer,
        combine_positiony integer,
        offsetx float(53),
        offsety float(53),
        scale float(53),
        created_at datetime default current_timestamp,
        id bigint unsigned not null auto_increment,
        updated_at datetime default current_timestamp on update current_timestamp,
        user_id bigint,
        nickname varchar(20),
        introduction varchar(50),
        avatar_body_uri varchar(255),
        avatar_face_uri varchar(255),
        avatar_icon_uri varchar(255),
        wallet_address varchar(255),
        avatar_composition_type enum ('SINGLE_BODY','BODY_WITH_FACE'),
        face_source_type enum ('INTERNAL_IMAGE','NFT_URI'),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create index crew_partyroom_id_user_id_IDX 
       on crew (partyroom_id, user_id);

    create index crew_user_id_is_active_IDX 
       on crew (user_id, is_active);

    alter table crew 
       add constraint uk_crew_partyroom_user unique (partyroom_id, user_id);

    create index dj_partyroom_id_IDX 
       on dj (partyroom_id);

    alter table member 
       add constraint unique_user_email unique (email);

    create index partyroom_host_id_IDX 
       on partyroom (host_id);

    create index playback_partyroom_id_IDX 
       on playback (partyroom_id);

    alter table playback_reaction_history 
       add constraint uk_reaction_user_playback unique (user_id, playback_id);

    create index playlist_owner_id_IDX 
       on playlist (owner_id);

    create index track_playlist_id_IDX 
       on track (playlist_id);

    alter table user_account 
       add constraint UK_c1h6pvllpm7eua8go0j2vbrqs unique (profile_id);

    create index user_activity_user_id_IDX 
       on user_activity (user_id);

    alter table user_activity 
       add constraint uk_activity_user_type unique (user_id, activity_type);

    create index user_profile_user_id_IDX 
       on user_profile (user_id);

    alter table guest 
       add constraint FKck785l9c9dndbehieafh52gyp 
       foreign key (user_id) 
       references user_account (user_id);

    alter table member 
       add constraint FK1ikd302qh85062tlmwibvoo20 
       foreign key (user_id) 
       references user_account (user_id);

    alter table user_account 
       add constraint FK5xiwy2xbrqbw53vpscp91sv00 
       foreign key (profile_id) 
       references user_profile (id);
