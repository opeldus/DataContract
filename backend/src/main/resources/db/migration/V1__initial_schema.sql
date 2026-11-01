create table mapping (
    id uuid primary key,
    name varchar(200) not null,
    contract_name varchar(100) not null unique,
    contract_version varchar(50) not null,
    domain varchar(100),
    description varchar(4000),
    target_table varchar(200),
    owner_team varchar(100),
    status varchar(30) not null,
    published_version varchar(50),
    published_commit varchar(64),
    created_by varchar(100) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null
);

create table mapping_rule (
    id uuid primary key,
    mapping_id uuid not null references mapping (id) on delete cascade,
    sort_order integer not null,
    source_dataset varchar(300),
    source_column varchar(200),
    target_column varchar(200),
    target_type varchar(100),
    required boolean not null,
    primary_key boolean not null,
    transformation varchar(4000),
    description varchar(4000),
    requirement_key varchar(50)
);

create index idx_mapping_rule_mapping on mapping_rule (mapping_id);

create table review_event (
    id uuid primary key,
    mapping_id uuid not null references mapping (id) on delete cascade,
    type varchar(30) not null,
    actor varchar(100) not null,
    comment varchar(4000),
    rule_id uuid,
    content_hash varchar(64),
    created_at timestamp with time zone not null
);

create index idx_review_event_mapping on review_event (mapping_id, created_at);

create table catalog_dataset (
    qualified_name varchar(300) primary key,
    urn varchar(500) not null,
    platform varchar(100) not null
);

create table catalog_column (
    id uuid primary key,
    dataset_name varchar(300) not null references catalog_dataset (qualified_name) on delete cascade,
    name varchar(200) not null,
    native_type varchar(100),
    nullable boolean not null,
    description varchar(4000)
);

create index idx_catalog_column_dataset on catalog_column (dataset_name);
