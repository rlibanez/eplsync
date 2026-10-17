ALTER TABLE telegram_settings DROP COLUMN base_url;
ALTER TABLE scheduled_tasks ADD COLUMN telegram_notifications boolean NOT NULL DEFAULT true;
ALTER TABLE telegram_deliveries ADD COLUMN task_id varchar(36) REFERENCES scheduled_tasks(id) ON DELETE CASCADE;
DELETE FROM telegram_deliveries WHERE state='PENDING';
