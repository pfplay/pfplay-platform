-- =====================================================
-- V13: Add must_change_password flag to user_account
--
-- Drives §5.6 forced password change flow. New admins (via POST /administrators)
-- and reset-password targets land with must_change_password=1; the flag clears
-- on successful self-change. Default 0 for all existing rows.
--
-- The V5-seeded super admin (user_id=1) keeps the default 0 — operator-supplied
-- ADMIN_SEED_PASSWORD is operator-chosen, so no forced-change is appropriate.
-- =====================================================

ALTER TABLE user_account
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT 0
        COMMENT 'Forces self-service password change at next login (§5.6).';
