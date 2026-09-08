create table users (
    id           uuid primary key,
    issuer       text        not null,
    subject      text        not null,
    username     text        not null,
    email        text,
    created_at   timestamptz not null,
    last_seen_at timestamptz not null
);

create unique index users_issuer_subject on users (issuer, subject);

alter table agent_runs
    add column created_by uuid references users (id);

create index agent_runs_created_by on agent_runs (created_by, created_at desc);
