-- Review history now covers mappings and data products. The table is rebuilt because the name of the foreign key on
-- review_event differs between PostgreSQL and H2.
create table review_log (
    id uuid primary key,
    subject_id uuid not null,
    type varchar(30) not null,
    actor varchar(100) not null,
    comment varchar(4000),
    rule_id uuid,
    content_hash varchar(64),
    created_at timestamp with time zone not null
);

insert into review_log (id, subject_id, type, actor, comment, rule_id, content_hash, created_at)
select id, mapping_id, type, actor, comment, rule_id, content_hash, created_at from review_event;

drop table review_event;

create index idx_review_log_subject on review_log (subject_id, created_at);

create table contract_version (
    mapping_id uuid not null references mapping (id),
    version varchar(50) not null,
    commit_id varchar(64) not null,
    published_at timestamp with time zone not null,
    primary key (mapping_id, version)
);

insert into contract_version (mapping_id, version, commit_id, published_at)
select id, published_version, published_commit, updated_at from mapping
where published_version is not null and published_commit is not null;

create table data_product (
    id uuid primary key,
    name varchar(200) not null,
    product_name varchar(100) not null unique,
    product_version varchar(50) not null,
    domain varchar(100),
    product_type varchar(50),
    purpose varchar(4000),
    owner_team varchar(100),
    status varchar(30) not null,
    published_version varchar(50),
    published_commit varchar(64),
    created_by varchar(100) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null
);

create table data_product_port (
    id uuid primary key,
    product_id uuid not null references data_product (id) on delete cascade,
    direction varchar(10) not null,
    sort_order integer not null,
    name varchar(200),
    contract_id uuid references mapping (id),
    contract_version varchar(50)
);

create index idx_data_product_port_product on data_product_port (product_id);
create index idx_data_product_port_contract on data_product_port (contract_id);
