create table manual_channel_profiles (
    id uuid primary key,
    tenant_id varchar(100) not null,
    channel_type varchar(40) not null,
    enabled boolean not null,
    account_alias varchar(120),
    default_tags_json text not null,
    default_section varchar(200),
    notes varchar(1000),
    sort_order integer not null,
    login_confirmed_at timestamp with time zone,
    profile_version integer not null,
    created_by varchar(200) not null,
    updated_by varchar(200) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_manual_channel_profiles_channel unique (tenant_id, channel_type),
    constraint ck_manual_channel_profiles_sort check (sort_order between 0 and 1000),
    constraint ck_manual_channel_profiles_version check (profile_version >= 1)
);

create index idx_manual_channel_profiles_tenant_order
    on manual_channel_profiles(tenant_id, enabled desc, sort_order, channel_type);
