create table article_assets (
    id uuid primary key,
    tenant_id varchar(100) not null,
    article_id uuid not null references articles(id),
    original_filename varchar(120) not null,
    content_type varchar(40) not null,
    byte_size bigint not null,
    created_by varchar(200) not null,
    created_at timestamp with time zone not null,
    constraint ck_article_assets_size check (byte_size > 0),
    constraint ck_article_assets_type check (content_type in ('image/jpeg', 'image/png', 'image/gif', 'image/webp'))
);

create index idx_article_assets_article on article_assets(tenant_id, article_id, created_at desc);
