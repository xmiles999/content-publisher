alter table article_drafts add column revision bigint not null default 1;

create table workspace_drafts (
    tenant_id varchar(100) not null,
    actor_subject varchar(200) not null,
    draft_key varchar(200) not null,
    content_json text not null,
    revision bigint not null check (revision > 0),
    updated_at timestamp with time zone not null,
    primary key (tenant_id, actor_subject, draft_key)
);
