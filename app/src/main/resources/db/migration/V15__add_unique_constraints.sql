-- Enforce uniqueness for user-facing identifiers that previously allowed
-- silent collisions (no DB constraint, no service-level pre-check).
-- Pre-deploy step: stg/dev data must be wiped of duplicates before this
-- migration runs, otherwise the ALTER will fail.

ALTER TABLE user_profile
    ADD CONSTRAINT uk_user_profile_nickname UNIQUE (nickname);

ALTER TABLE partyroom
    ADD CONSTRAINT uk_partyroom_link_domain UNIQUE (link_domain);
