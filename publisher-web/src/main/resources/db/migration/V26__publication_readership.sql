create table publication_readership (
    tenant_id varchar(100) not null,
    article_id uuid not null references articles(id),
    publication_method varchar(10) not null check (publication_method in ('API', 'MANUAL')),
    publication_id uuid not null,
    views bigint check (views >= 0),
    unique_readers bigint check (unique_readers >= 0),
    source varchar(10) check (source in ('API', 'MANUAL')),
    recorded_at timestamp with time zone,
    recorded_by varchar(200),
    last_sync_at timestamp with time zone,
    sync_error varchar(500),
    version integer not null check (version > 0),
    primary key (tenant_id, publication_method, publication_id),
    check (views is null or unique_readers is null or unique_readers <= views)
);
create index idx_readership_article on publication_readership(tenant_id, article_id);
