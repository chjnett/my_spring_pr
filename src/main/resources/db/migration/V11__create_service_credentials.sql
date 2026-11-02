create table service_credentials (
    id bigserial primary key,
    credential_id varchar(50) not null unique,
    source_id varchar(50) not null,
    secret_hash varchar(100) not null,
    active boolean not null default true,
    expires_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    revoked_at timestamptz,
    last_used_at timestamptz,
    constraint chk_service_credentials_id
        check (credential_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$'),
    constraint chk_service_credentials_source
        check (source_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$')
);

create index idx_service_credentials_active_expiry
    on service_credentials (credential_id)
    where active = true;
