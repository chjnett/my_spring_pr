alter table security_events
    add column source varchar(50);

alter table security_events
    add column source_ip varchar(45);

alter table security_events
    add column outcome varchar(20);

create index idx_security_events_occurred_id
    on security_events (occurred_at desc, id desc);

create index idx_security_events_source_ip_occurred
    on security_events (source_ip, occurred_at desc, id desc);

create index idx_security_events_outcome_occurred
    on security_events (outcome, occurred_at desc, id desc);
