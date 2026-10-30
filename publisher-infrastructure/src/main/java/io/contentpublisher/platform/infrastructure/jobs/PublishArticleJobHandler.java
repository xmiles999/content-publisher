package io.contentpublisher.platform.infrastructure.jobs;

import io.contentpublisher.platform.application.JobProgressReporter;
import io.contentpublisher.platform.application.PublicationCommandApplicationService;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobType;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class PublishArticleJobHandler implements JobHandler {
    private final PublicationCommandApplicationService publishing;

    public PublishArticleJobHandler(PublicationCommandApplicationService publishing) {
        this.publishing = publishing;
    }

    @Override
    public JobType type() {
        return JobType.PUBLISH_ARTICLE;
    }

    @Override
    public UUID handle(Job job, ActorContext actor, JobProgressReporter progress) {
        JobPayload.PublishArticle payload = (JobPayload.PublishArticle) job.payload();
        return publishing.publish(actor, payload.articleId(), payload.channelAccountId(),
                payload.canonicalUrl(), job.id(), progress, payload.articleVersion(), true).id();
    }
}
