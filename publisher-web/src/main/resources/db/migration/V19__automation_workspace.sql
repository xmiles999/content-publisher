create table article_drafts (
    id uuid primary key,
    tenant_id varchar(100) not null,
    article_id uuid not null references articles(id) on delete cascade,
    actor_subject varchar(200) not null,
    base_version integer not null,
    title varchar(500) not null,
    summary varchar(2000) not null,
    markdown text not null,
    tags_json text not null,
    keywords_json text not null,
    title_en varchar(500),
    summary_en varchar(2000),
    markdown_en text,
    tags_en_json text not null,
    keywords_en_json text not null,
    updated_at timestamp with time zone not null,
    constraint uk_article_drafts_owner unique (tenant_id, article_id, actor_subject),
    constraint ck_article_drafts_version check (base_version >= 1)
);

create index idx_article_drafts_tenant_updated
    on article_drafts(tenant_id, updated_at desc);

create table generation_presets (
    id uuid primary key,
    tenant_id varchar(100) not null,
    name varchar(120) not null,
    source_type varchar(20) not null,
    language varchar(20),
    tone varchar(100) not null,
    min_characters integer not null,
    max_characters integer not null,
    max_keywords integer not null,
    required_sections text not null,
    article_type varchar(40),
    knowledge_level varchar(40),
    recommendation_angle varchar(2000),
    model varchar(200),
    prompt_version varchar(100),
    parameter_version varchar(100),
    usage_count bigint not null default 0,
    created_by varchar(200) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_generation_presets_name unique (tenant_id, source_type, name),
    constraint ck_generation_presets_source check (source_type in ('PROJECT', 'TOPIC', 'WEBSITE')),
    constraint ck_generation_presets_lengths check (
        min_characters >= 200 and max_characters >= min_characters
        and max_characters <= 3000 and max_keywords between 1 and 30
    )
);

create index idx_generation_presets_tenant_source
    on generation_presets(tenant_id, source_type, usage_count desc, updated_at desc);

create table notifications (
    id uuid primary key,
    tenant_id varchar(100) not null,
    type varchar(60) not null,
    severity varchar(20) not null,
    dedup_key varchar(200) not null,
    title varchar(200) not null,
    message varchar(1000) not null,
    target_url varchar(2048),
    acknowledged_at timestamp with time zone,
    acknowledged_by varchar(200),
    resolved_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    webhook_delivered_at timestamp with time zone,
    constraint uk_notifications_dedup unique (tenant_id, dedup_key),
    constraint ck_notifications_severity check (severity in ('INFO', 'WARNING', 'ERROR'))
);

create index idx_notifications_tenant_open
    on notifications(tenant_id, acknowledged_at, resolved_at, created_at desc);

create table notification_endpoints (
    id uuid primary key,
    tenant_id varchar(100) not null,
    display_name varchar(120) not null,
    webhook_url varchar(2048) not null,
    enabled boolean not null,
    created_by varchar(200) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_notification_endpoints_name unique (tenant_id, display_name)
);

create table manual_publication_progress (
    id uuid primary key,
    tenant_id varchar(100) not null,
    article_id uuid not null references articles(id) on delete cascade,
    channel_type varchar(40) not null,
    actor_subject varchar(200) not null,
    copied_title boolean not null,
    copied_content boolean not null,
    opened_editor boolean not null,
    checked_format boolean not null,
    published boolean not null,
    updated_at timestamp with time zone not null,
    constraint uk_manual_progress_owner unique (tenant_id, article_id, channel_type, actor_subject)
);

create index idx_manual_progress_tenant_updated
    on manual_publication_progress(tenant_id, updated_at desc);
