alter table audit_outbox
    add column correlation_id varchar(64);

alter table audit_outbox
    add column integrity_version integer;

alter table security_events
    add column correlation_id varchar(64);

alter table security_events
    add column integrity_version integer;

create index idx_security_events_correlation_id
    on security_events (correlation_id);
