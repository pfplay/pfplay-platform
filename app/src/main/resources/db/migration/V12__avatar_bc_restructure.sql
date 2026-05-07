-- =====================================================
-- V14: Avatar BC Restructure
--
-- See docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.11 for rationale.
--
-- Changes:
--   1. body/face: ADD icon_uri, lifecycle_status, audit columns
--   2. face: ADD obtainable_type (BASIC fixed, future expansion)
--   3. Data transfer from avatar_icon_resource into parent icon_uri
--      using name-prefix convention (no dependency on pair_type ordinal)
--   4. DROP avatar_icon_resource
-- =====================================================

-- Step 1. body: icon_uri, lifecycle, 감사 컬럼
ALTER TABLE avatar_body_resource
    ADD COLUMN icon_uri         VARCHAR(500) NULL        AFTER resource_uri,
    ADD COLUMN lifecycle_status VARCHAR(16)  NOT NULL
        DEFAULT 'PUBLISHED'                               AFTER is_default_setting,
    ADD COLUMN created_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP                         AFTER combine_positiony,
    ADD COLUMN created_by       BIGINT       NULL        AFTER created_at,
    ADD COLUMN updated_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP                       AFTER created_by,
    ADD COLUMN updated_by       BIGINT       NULL        AFTER updated_at;

ALTER TABLE avatar_body_resource
    ADD CONSTRAINT chk_body_lifecycle
        CHECK (lifecycle_status IN ('DRAFT','PUBLISHED','RETIRED'));

-- Step 2. face: icon_uri, lifecycle, obtainable_type, 감사 컬럼
ALTER TABLE avatar_face_resource
    ADD COLUMN icon_uri         VARCHAR(500) NULL        AFTER resource_uri,
    ADD COLUMN obtainable_type  VARCHAR(16)  NOT NULL
        DEFAULT 'BASIC'                                   AFTER icon_uri,
    ADD COLUMN lifecycle_status VARCHAR(16)  NOT NULL
        DEFAULT 'PUBLISHED'                               AFTER obtainable_type,
    ADD COLUMN created_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by       BIGINT       NULL,
    ADD COLUMN updated_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    ADD COLUMN updated_by       BIGINT       NULL;

ALTER TABLE avatar_face_resource
    ADD CONSTRAINT chk_face_lifecycle
        CHECK (lifecycle_status IN ('DRAFT','PUBLISHED','RETIRED')),
    ADD CONSTRAINT chk_face_obtainable
        CHECK (obtainable_type = 'BASIC');

-- Step 3. Data transfer from avatar_icon_resource → parent.icon_uri
--   Matches by name-prefix (does NOT depend on pair_type ordinal).
UPDATE avatar_body_resource b
INNER JOIN avatar_icon_resource i
        ON i.name LIKE 'ava_icon_body_%'
       AND i.name = CONCAT('ava_icon_', SUBSTRING(b.name, 5))
SET b.icon_uri = i.resource_uri;

UPDATE avatar_face_resource f
INNER JOIN avatar_icon_resource i
        ON i.name LIKE 'ava_icon_face_%'
       AND i.name = CONCAT('ava_icon_', SUBSTRING(f.name, 5))
SET f.icon_uri = i.resource_uri;

-- Step 4. Drop icon resource table (and PairType enum will be removed from Java in same PR)
DROP TABLE avatar_icon_resource;
