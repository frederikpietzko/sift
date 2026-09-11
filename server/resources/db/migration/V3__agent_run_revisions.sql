alter table agent_runs
    add column supersedes_run_id uuid references agent_runs (id) on delete set null;

create index agent_runs_supersedes_run_id on agent_runs (supersedes_run_id);
