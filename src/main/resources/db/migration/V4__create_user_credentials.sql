create table user_credentials (
    user_id bigint primary key,
    password_hash varchar(100) not null,
    created_at timestamp with time zone not null,
    constraint fk_user_credentials_user
        foreign key (user_id) references users (id) on delete cascade
);
