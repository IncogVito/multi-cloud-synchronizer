-- Capture-time UTC offset for each photo, stored as an ISO-8601 offset id (e.g. "+12:00", "Z").
-- created_date stays a true-UTC instant; this column lets the UI reconstruct the local wall-clock
-- the shot was taken at, so photos sort/display by capture-local time regardless of the viewer's TZ.
-- NULL means the source carried no timezone information (e.g. plain EXIF without an OffsetTime tag).
ALTER TABLE photos ADD COLUMN created_date_timezone TEXT;
