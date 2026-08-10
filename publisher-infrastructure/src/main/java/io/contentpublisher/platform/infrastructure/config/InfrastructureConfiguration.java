package io.contentpublisher.platform.infrastructure.config;

import io.contentpublisher.platform.application.AiSettingsApplicationService;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.ArticleEditorialApplicationService;
import io.contentpublisher.platform.application.ChannelAccountApplicationService;
import io.contentpublisher.platform.application.ContentGenerationApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.ProjectImportApplicationService;
import io.contentpublisher.platform.application.PublicationCommandApplicationService;
import io.contentpublisher.platform.application.PublicationQueryApplicationService;
import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.application.RecordManagementApplicationService;
import io.contentpublisher.platform.application.PlatformContentAdapter;
import io.contentpublisher.platform.application.MonitoringApplicationService;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AutomationRepository;
import io.contentpublisher.platform.application.port.AiEndpointPolicy;
import io.contentpublisher.platform.application.port.AiProviderSettingsRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ContentGenerator;
import io.contentpublisher.platform.application.port.ProjectRepository;
import io.contentpublisher.platform.application.port.RepositoryInspector;
import io.contentpublisher.platform.application.port.RepositorySnapshotStore;
import io.contentpublisher.platform.application.port.SecretCipher;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.PublicationRepository;
import io.contentpublisher.platform.application.port.ManualPublicationRepository;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.ChannelPublisher;
import io.contentpublisher.platform.application.port.ChannelConnectionVerifier;
import io.contentpublisher.platform.application.port.ChannelCredentialRefresher;
import io.contentpublisher.platform.application.port.WebsiteInspector;
import io.contentpublisher.platform.application.port.MonitoringQuery;
import io.contentpublisher.platform.application.port.WebhookEndpointPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({GitImportProperties.class, AiProperties.class, AiEndpointSecurityProperties.class,
        SecretProperties.class, JobProperties.class, ChannelProperties.class, WebsiteImportProperties.class,
        AutomationProperties.class})
public class InfrastructureConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient aiHttpClient(AiProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    HttpClient channelHttpClient(ChannelProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Bean
    HttpClient websiteHttpClient(WebsiteImportProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    HttpClient webhookHttpClient(AutomationProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.webhookTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    ProjectImportApplicationService projectImportApplicationService(ProjectRepository projects,
                                                                    RepositorySnapshotStore snapshots,
                                                                    RepositoryInspector inspector,
                                                                    AuditRecorder auditRecorder,
                                                                    Clock clock) {
        return new ProjectImportApplicationService(projects, snapshots, inspector, auditRecorder, clock);
    }

    @Bean
    ContentGenerationApplicationService contentGenerationApplicationService(ProjectRepository projects,
                                                                            ArticleRepository articles,
                                                                            RepositorySnapshotStore snapshots,
                                                                            WebsiteInspector websiteInspector,
                                                                            ContentGenerator generator,
                                                                            AuditRecorder auditRecorder,
                                                                            Clock clock) {
        return new ContentGenerationApplicationService(projects, articles, snapshots, websiteInspector, generator,
                auditRecorder, clock);
    }

    @Bean
    ProjectApplicationService projectApplicationService(ProjectRepository projects, ArticleRepository articles,
                                                         ProjectImportApplicationService imports,
                                                         ContentGenerationApplicationService generation) {
        return new ProjectApplicationService(projects, articles, imports, generation);
    }

    @Bean
    PlatformContentAdapter platformContentAdapter() {
        return new PlatformContentAdapter();
    }

    @Bean
    ChannelAccountApplicationService channelAccountApplicationService(ChannelAccountRepository accounts,
                                                                      CredentialVault credentialVault,
                                                                      ChannelEndpointPolicy endpointPolicy,
                                                                      AuditRecorder auditRecorder,
                                                                      ChannelConnectionVerifier connectionVerifier,
                                                                      Clock clock) {
        return new ChannelAccountApplicationService(accounts, credentialVault, endpointPolicy, auditRecorder,
                connectionVerifier, clock);
    }

    @Bean
    ArticleEditorialApplicationService articleEditorialApplicationService(ArticleRepository articles,
                                                                          AuditRecorder auditRecorder, Clock clock) {
        return new ArticleEditorialApplicationService(articles, auditRecorder, clock);
    }

    @Bean
    PublicationCommandApplicationService publicationCommandApplicationService(ArticleRepository articles,
                                                                              ChannelAccountRepository accounts,
                                                                              PublicationRepository publications,
                                                                              ManualPublicationRepository manual,
                                                                              CredentialVault credentialVault,
                                                                              ChannelEndpointPolicy endpointPolicy,
                                                                              ChannelCredentialRefresher credentialRefresher,
                                                                              List<ChannelPublisher> publishers,
                                                                              AuditRecorder auditRecorder,
                                                                              PlatformContentAdapter contentAdapter,
                                                                              Clock clock) {
        return new PublicationCommandApplicationService(articles, accounts, publications, manual, credentialVault,
                endpointPolicy, credentialRefresher, publishers, auditRecorder, contentAdapter, clock);
    }

    @Bean
    PublicationQueryApplicationService publicationQueryApplicationService(ArticleRepository articles,
                                                                          ChannelAccountRepository accounts,
                                                                          PublicationRepository publications,
                                                                          ManualPublicationRepository manual) {
        return new PublicationQueryApplicationService(articles, accounts, publications, manual);
    }

    @Bean
    PublishingApplicationService publishingApplicationService(ChannelAccountApplicationService accounts,
                                                               ArticleEditorialApplicationService articles,
                                                               PublicationCommandApplicationService commands,
                                                               PublicationQueryApplicationService queries) {
        return new PublishingApplicationService(accounts, articles, commands, queries);
    }

    @Bean
    JobApplicationService jobApplicationService(JobRepository jobs, ProjectApplicationService projects,
                                                PublishingApplicationService publishing,
                                                AuditRecorder auditRecorder, Clock clock, JobProperties properties) {
        return new JobApplicationService(jobs, projects, publishing, auditRecorder, clock,
                properties.maxActiveJobsPerTenant(), properties.maxAttempts());
    }

    @Bean
    RecordManagementApplicationService recordManagementApplicationService(ArticleRepository articles,
                                                                           JobRepository jobs,
                                                                           AuditRecorder auditRecorder,
                                                                           Clock clock) {
        return new RecordManagementApplicationService(articles, jobs, auditRecorder, clock);
    }

    @Bean
    MonitoringApplicationService monitoringApplicationService(MonitoringQuery monitoring, Clock clock) {
        return new MonitoringApplicationService(monitoring, clock);
    }

    @Bean
    AutomationApplicationService automationApplicationService(AutomationRepository automation,
                                                               ArticleRepository articles,
                                                               AuditRecorder auditRecorder,
                                                               WebhookEndpointPolicy webhookEndpointPolicy,
                                                               Clock clock) {
        return new AutomationApplicationService(automation, articles, auditRecorder, webhookEndpointPolicy, clock);
    }

    @Bean
    AiSettingsApplicationService aiSettingsApplicationService(AiProviderSettingsRepository settings,
                                                               AiEndpointPolicy endpointPolicy,
                                                               SecretCipher secretCipher,
                                                               AuditRecorder auditRecorder,
                                                               Clock clock) {
        return new AiSettingsApplicationService(settings, endpointPolicy, secretCipher, auditRecorder, clock);
    }
}
