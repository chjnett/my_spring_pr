alter table audit_outbox
    add column integrity_key_id varchar(50);

alter table audit_outbox
    add column integrity_signature varchar(64);

alter table security_events
    add column integrity_key_id varchar(50);

alter table security_events
    add column integrity_signature varchar(64);
