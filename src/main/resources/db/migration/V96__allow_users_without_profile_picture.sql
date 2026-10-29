ALTER TABLE users
    ALTER COLUMN profile_picture DROP NOT NULL;

ALTER TABLE pending_registrations
    ALTER COLUMN profile_picture DROP NOT NULL;

UPDATE users
SET profile_picture = NULL
WHERE profile_picture = 'https://default-image.png';

UPDATE pending_registrations
SET profile_picture = NULL
WHERE profile_picture = 'https://default-image.png';
