alter table channel_accounts add column deleted_at timestamp with time zone;
alter table channel_accounts add column deleted_by varchar(200);

create index idx_channel_accounts_tenant_active_created
    on channel_accounts(tenant_id, deleted_at, created_at desc);
