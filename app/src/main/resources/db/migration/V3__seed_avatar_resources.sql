-- Move avatar resource seed from ApplicationReadyEventListener to Flyway.
--
-- Why:
--   Java initializer with skip-if-exists still re-creates rows that an admin
--   later deletes via runtime management. Flyway runs exactly once per env,
--   then the table is fully owned by admin UI / future migrations.
--
-- Idempotency:
--   We add UNIQUE constraints on the natural key (name, +pair_type for icon)
--   and use INSERT IGNORE so existing local/dev DBs (already populated by
--   the old Java initializer) are safe to migrate without manual wiping.

-- 1. Enforce name uniqueness (was implied by the seed code's findByName but never enforced at the DB level)
ALTER TABLE avatar_body_resource
    ADD CONSTRAINT uk_avatar_body_name UNIQUE (name);

ALTER TABLE avatar_face_resource
    ADD CONSTRAINT uk_avatar_face_name UNIQUE (name);

ALTER TABLE avatar_icon_resource
    ADD CONSTRAINT uk_avatar_icon_name_pair UNIQUE (name, pair_type);

-- 2. Body resources (15 rows: 3 basic + 12 DJ-unlock)
INSERT IGNORE INTO avatar_body_resource
    (name, resource_uri, obtainable_type, obtainable_score, is_combinable, is_default_setting, combine_positionx, combine_positiony)
VALUES
    ('ava_body_basic_001', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media', 'BASIC',     0, 1, 1, 60, 41),
    ('ava_body_basic_002', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_002.png?alt=media', 'BASIC',     0, 0, 0,  0,  0),
    ('ava_body_basic_003', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_003.png?alt=media', 'BASIC',     0, 0, 0,  0,  0),
    ('ava_body_djing_001', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_001.png?alt=media', 'DJ_PNT',   10, 0, 0,  0,  0),
    ('ava_body_djing_002', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_002.png?alt=media', 'DJ_PNT',   25, 0, 0,  0,  0),
    ('ava_body_djing_003', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_003.png?alt=media', 'DJ_PNT',   60, 1, 0, 60, 39),
    ('ava_body_djing_004', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_004.png?alt=media', 'DJ_PNT',  100, 1, 0, 60, 45),
    ('ava_body_djing_005', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_005.png?alt=media', 'DJ_PNT',  150, 1, 0, 60, 40),
    ('ava_body_djing_006', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_006.png?alt=media', 'DJ_PNT',  200, 1, 0, 52, 39),
    ('ava_body_djing_007', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_007.png?alt=media', 'DJ_PNT',  500, 1, 0, 60, 40),
    ('ava_body_djing_008', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_008.png?alt=media', 'DJ_PNT', 1000, 1, 0, 60, 40),
    ('ava_body_djing_009', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_009.png?alt=media', 'DJ_PNT', 2000, 1, 0, 60, 42),
    ('ava_body_djing_010', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_010.png?alt=media', 'DJ_PNT', 4000, 1, 0, 58, 43),
    ('ava_body_djing_011', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_011.png?alt=media', 'DJ_PNT', 7000, 1, 0, 60, 44),
    ('ava_body_djing_012', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_012.png?alt=media', 'DJ_PNT',10000, 1, 0, 56, 38);

-- 3. Face resource (1 row)
INSERT IGNORE INTO avatar_face_resource
    (name, resource_uri)
VALUES
    ('ava_face_basic_001', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_face%2Fava_face_001.png?alt=media');

-- 4. Icon resources (5 rows). pair_type: BODY=0, FACE=1 (ORDINAL mapping of PairType enum)
INSERT IGNORE INTO avatar_icon_resource
    (name, resource_uri, pair_type)
VALUES
    ('ava_icon_face_basic_001', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_face_basic_001.png?alt=media', 1),
    ('ava_icon_body_basic_002', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_basic_002.png?alt=media', 0),
    ('ava_icon_body_basic_003', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_basic_003.png?alt=media', 0),
    ('ava_icon_body_djing_001', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_djing_001.png?alt=media', 0),
    ('ava_icon_body_djing_002', 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_djing_002.png?alt=media', 0);
