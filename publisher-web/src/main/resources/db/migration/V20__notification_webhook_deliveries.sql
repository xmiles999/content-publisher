create table notification_webhook_deliveries (
    id uuid primary key,
    notification_id uuid not null references notifications(id) on delete cascade,
    endpoint_id uuid not null references notification_endpoints(id) on delete cascade,
    tenant_id varchar(100) not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp with time zone not null,
    delivered_at timestamp with time zone,
    last_error varchar(500),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_notification_webhook_delivery unique (notification_id, endpoint_id),
    constraint ck_notification_webhook_delivery_status check (status in ('PENDING', 'DELIVERED', 'FAILED')),
    constraint ck_notification_webhook_delivery_attempts check (attempts >= 0)
);

create index idx_notification_webhook_deliveries_due
    on notification_webhook_deliveries(status, next_attempt_at, created_at);
