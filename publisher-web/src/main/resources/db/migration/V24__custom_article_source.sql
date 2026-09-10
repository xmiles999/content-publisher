alter table articles drop constraint ck_articles_source;

alter table articles add constraint ck_articles_source
    check ((source_type = 'GIT' and project_id is not null)
        or (source_type in ('TOPIC', 'WEBSITE', 'CUSTOM') and project_id is null));
