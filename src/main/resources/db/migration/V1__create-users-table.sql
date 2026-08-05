CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(60) NOT NULL,
    email VARCHAR(60) NOT NULL,
    password VARCHAR(255) NOT NULL,
    description VARCHAR(280),
    profile_picture VARCHAR(2048) NOT NULL,
    is_profile_public BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE UNIQUE INDEX uq_users_email ON users (LOWER(email));
