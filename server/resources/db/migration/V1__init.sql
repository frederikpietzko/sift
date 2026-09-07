create table repositories (
    id               uuid primary key,
    name             text        unique not null,
    url              text        not null,
    token_ciphertext bytea,
    token_iv         bytea,
    secret_name      text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create table agent_runs (
    id            uuid primary key,
    kind          text        not null,
    source        text        not null default 'API',
    repository_id uuid references repositories (id),
    cr_name       text        not null,
    cr_uid        text,
    generation    bigint,
    execution_id  text,
    phase         text        not null,
    reason        text,
    message       text,
    spec          jsonb       not null,
    created_at    timestamptz not null,
    started_at    timestamptz,
    completed_at  timestamptz,
    observed_at   timestamptz,
    updated_at    timestamptz not null
);

create unique index agent_runs_cr_uid on agent_runs (cr_uid);
create index agent_runs_phase on agent_runs (phase, created_at desc);

create table review_results (
    id             uuid primary key,
    execution_id   text        unique not null,
    agent_run_id   uuid references agent_runs (id),
    repository_url text        not null,
    branch         text        not null,
    base_branch    text        not null,
    commit_sha     text        not null,
    pull_request   text,
    summary        text        not null,
    completed_at   timestamptz not null,
    received_at    timestamptz not null
);

create index review_results_repo_commit on review_results (repository_url, commit_sha);

create table review_findings (
    id         bigserial primary key,
    result_id  uuid not null references review_results (id) on delete cascade,
    file       text not null,
    start_line int,
    end_line   int,
    severity   text not null,
    category   text,
    message    text not null,
    suggestion text
);

create index review_findings_result_severity on review_findings (result_id, severity);
create index review_findings_file on review_findings (file);

create function notify_agent_run() returns trigger language plpgsql as $$
begin
    perform pg_notify(
        'sift_agent_runs',
        json_build_object('id', new.id, 'phase', new.phase, 'updatedAt', new.updated_at)::text
    );
    return new;
end
$$;

create trigger agent_runs_notify
    after insert or update on agent_runs
    for each row execute function notify_agent_run();
