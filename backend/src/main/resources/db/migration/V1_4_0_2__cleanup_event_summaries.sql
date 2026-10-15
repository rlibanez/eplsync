-- Notification state is independent of event retention and survives application restarts.
ALTER TABLE torrent_update_cleanup ADD COLUMN event_signature TEXT;
