-- Local copy of what Jira knows about referenced issues; Jira stays the system of record.
create table requirement_issue (
    issue_key varchar(50) primary key,
    found boolean not null,
    summary varchar(1000),
    status_name varchar(100),
    status_category varchar(50),
    issue_type varchar(100),
    checked_at timestamp with time zone not null
);

-- Outbound updates are written together with the publish and sent afterwards, so an outage cannot lose them.
create table integration_task (
    id uuid primary key,
    subject_id uuid not null,
    kind varchar(30) not null,
    target varchar(100),
    payload varchar(10000000) not null,
    state varchar(20) not null,
    attempts integer not null,
    last_error varchar(1000),
    next_attempt_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_integration_task_due on integration_task (state, next_attempt_at);
create index idx_integration_task_subject on integration_task (subject_id, created_at);

create table integration_page (
    subject_id uuid primary key,
    page_id varchar(50) not null,
    url varchar(1000),
    updated_at timestamp with time zone not null
);
