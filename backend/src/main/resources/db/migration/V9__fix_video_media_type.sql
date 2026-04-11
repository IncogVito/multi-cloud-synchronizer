-- Fix media_type for video files that were incorrectly stored as PHOTO
UPDATE photos
SET media_type = 'VIDEO'
WHERE (
    filename LIKE '%.mp4'
    OR filename LIKE '%.MP4'
    OR filename LIKE '%.m4v'
    OR filename LIKE '%.M4V'
    OR filename LIKE '%.mov'
    OR filename LIKE '%.MOV'
    OR filename LIKE '%.avi'
    OR filename LIKE '%.AVI'
    OR filename LIKE '%.mkv'
    OR filename LIKE '%.MKV'
)
AND media_type = 'PHOTO';
