-- Analysed DataStage jobs. Only the derived model is kept, never the export itself, which can contain connection details.
create table etl_job (
    id uuid primary key,
    name varchar(300) not null unique,
    kind varchar(20) not null,
    category varchar(1000),
    source_file varchar(500) not null,
    imported_by varchar(100) not null,
    imported_at timestamp with time zone not null,
    model varchar(10000000) not null
);
