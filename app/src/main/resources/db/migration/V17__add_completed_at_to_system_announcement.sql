ALTER TABLE system_announcement
  ADD COLUMN completed_at DATETIME NULL AFTER cancelled_at;
