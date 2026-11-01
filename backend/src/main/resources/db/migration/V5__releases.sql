-- Tables with changed unique constraints or foreign keys are rebuilt, because the generated constraint names differ
-- between PostgreSQL and H2. Everything that exists today belongs to the mainline, and its id doubles as its key.
create table release_plan (
    id uuid primary key,
    name varchar(50) not null unique,
    title varchar(200) not null,
    base_id uuid references release_plan (id),
    state varchar(20) not null,
    branch varchar(100) not null,
    created_by varchar(100) not null,
    created_at timestamp with time zone not null,
    released_at timestamp with time zone,
    released_commit varchar(64),
    version bigint not null
);

create table mapping_new (
    id uuid primary key,
    contract_key uuid not null,
    release_id uuid references release_plan (id),
    scope_key varchar(60) not null,
    name varchar(200) not null,
    contract_name varchar(100) not null,
    contract_version varchar(50) not null,
    domain varchar(100),
    description varchar(4000),
    target_table varchar(200),
    owner_team varchar(100),
    status varchar(30) not null,
    published_version varchar(50),
    upstream_version varchar(50),
    published_commit varchar(64),
    created_by varchar(100) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null,
    constraint uq_mapping_name_scope unique (contract_name, scope_key),
    constraint uq_mapping_key_scope unique (contract_key, scope_key)
);

insert into mapping_new (id, contract_key, release_id, scope_key, name, contract_name, contract_version, domain,
        description, target_table, owner_team, status, published_version, published_commit, created_by, created_at,
        updated_at, version)
select id, id, null, 'main', name, contract_name, contract_version, domain, description, target_table, owner_team,
        status, published_version, published_commit, created_by, created_at, updated_at, version
from mapping;

create table mapping_rule_new (
    id uuid primary key,
    lineage_id uuid not null,
    mapping_id uuid not null references mapping_new (id) on delete cascade,
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

insert into mapping_rule_new (id, lineage_id, mapping_id, sort_order, source_dataset, source_column, target_column,
        target_type, required, primary_key, transformation, description, requirement_key)
select id, id, mapping_id, sort_order, source_dataset, source_column, target_column, target_type, required, primary_key,
        transformation, description, requirement_key
from mapping_rule;

create table data_product_new (
    id uuid primary key,
    product_key uuid not null,
    release_id uuid references release_plan (id),
    scope_key varchar(60) not null,
    name varchar(200) not null,
    product_name varchar(100) not null,
    product_version varchar(50) not null,
    domain varchar(100),
    product_type varchar(50),
    purpose varchar(4000),
    owner_team varchar(100),
    status varchar(30) not null,
    published_version varchar(50),
    upstream_version varchar(50),
    published_commit varchar(64),
    created_by varchar(100) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null,
    constraint uq_product_name_scope unique (product_name, scope_key),
    constraint uq_product_key_scope unique (product_key, scope_key)
);

insert into data_product_new (id, product_key, release_id, scope_key, name, product_name, product_version, domain,
        product_type, purpose, owner_team, status, published_version, published_commit, created_by, created_at,
        updated_at, version)
select id, id, null, 'main', name, product_name, product_version, domain, product_type, purpose, owner_team, status,
        published_version, published_commit, created_by, created_at, updated_at, version
from data_product;

-- Ports point at a contract key, not at a row: which row applies depends on the release they are read in.
create table data_product_port_new (
    id uuid primary key,
    lineage_id uuid not null,
    product_id uuid not null references data_product_new (id) on delete cascade,
    direction varchar(10) not null,
    sort_order integer not null,
    name varchar(200),
    contract_key uuid,
    contract_version varchar(50)
);

insert into data_product_port_new (id, lineage_id, product_id, direction, sort_order, name, contract_key, contract_version)
select id, id, product_id, direction, sort_order, name, contract_id, contract_version from data_product_port;

create table published_version (
    subject_key uuid not null,
    version varchar(50) not null,
    commit_id varchar(64) not null,
    published_at timestamp with time zone not null,
    snapshot varchar(10000000),
    release_id uuid references release_plan (id),
    primary key (subject_key, version)
);

insert into published_version (subject_key, version, commit_id, published_at, snapshot, release_id)
select mapping_id, version, commit_id, published_at, snapshot, null from contract_version;

drop table contract_version;
drop table data_product_port;
drop table data_product;
drop table mapping_rule;
drop table mapping;

alter table mapping_new rename to mapping;
alter table mapping_rule_new rename to mapping_rule;
alter table data_product_new rename to data_product;
alter table data_product_port_new rename to data_product_port;

create index idx_mapping_scope on mapping (scope_key);
create index idx_mapping_contract_key on mapping (contract_key);
create index idx_mapping_rule_mapping on mapping_rule (mapping_id);
create index idx_data_product_scope on data_product (scope_key);
create index idx_data_product_port_product on data_product_port (product_id);
create index idx_data_product_port_contract on data_product_port (contract_key);

-- Differences that could not be merged automatically when an upstream release moved on.
create table merge_conflict (
    id uuid primary key,
    subject_id uuid not null,
    item_id uuid,
    field varchar(50),
    label varchar(300) not null,
    ours varchar(4000),
    theirs varchar(4000),
    theirs_item varchar(20000),
    upstream_version varchar(50) not null,
    state varchar(20) not null,
    created_at timestamp with time zone not null,
    resolved_by varchar(100),
    resolved_at timestamp with time zone
);

create index idx_merge_conflict_subject on merge_conflict (subject_id, state);
