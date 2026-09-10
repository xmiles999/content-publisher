package io.contentpublisher.platform.architecture;

import io.contentpublisher.platform.application.ArticleEditorialApplicationService;
import io.contentpublisher.platform.application.ChannelAccountApplicationService;
import io.contentpublisher.platform.application.ChannelCatalog;
import io.contentpublisher.platform.application.ContentGenerationApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.ProjectImportApplicationService;
import io.contentpublisher.platform.application.PublicationCommandApplicationService;
import io.contentpublisher.platform.application.PublicationQueryApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.application.port.AiProviderSettingsRepository;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.application.port.ManualChannelProfileRepository;
import io.contentpublisher.platform.application.port.ManualPublicationRepository;
import io.contentpublisher.platform.application.port.ProjectRepository;
import io.contentpublisher.platform.application.port.PublicationRepository;
import io.contentpublisher.platform.application.port.RepositorySnapshotStore;
import io.contentpublisher.platform.infrastructure.jobs.DurableJobWorker;
import io.contentpublisher.platform.infrastructure.jobs.JobHandler;
import io.contentpublisher.platform.infrastructure.persistence.JpaAiProviderSettingsPersistenceAdapter;
import io.contentpublisher.platform.infrastructure.persistence.JpaArticlePersistenceAdapter;
import io.contentpublisher.platform.infrastructure.persistence.JpaAuditRecorder;
import io.contentpublisher.platform.infrastructure.persistence.JpaJobPersistenceAdapter;
import io.contentpublisher.platform.infrastructure.persistence.JpaManualChannelProfilePersistenceAdapter;
import io.contentpublisher.platform.infrastructure.persistence.JpaProjectPersistenceAdapter;
import io.contentpublisher.platform.infrastructure.persistence.JpaPublishingPersistenceAdapter;
import io.contentpublisher.platform.web.controller.ContentCreationPortalController;
import io.contentpublisher.platform.web.controller.ContentLibraryPortalController;
import io.contentpublisher.platform.web.controller.JobPortalController;
import io.contentpublisher.platform.web.controller.RecycleBinPortalController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureBoundaryTest {
    @Test
    void publishingFacadeShouldOnlyCoordinateFocusedServices() {
        assertThat(fieldTypes(PublishingApplicationService.class)).containsExactlyInAnyOrder(
                ChannelAccountApplicationService.class,
                ArticleEditorialApplicationService.class,
                PublicationCommandApplicationService.class,
                PublicationQueryApplicationService.class);
    }

    @Test
    void projectFacadeShouldDelegateWriteResponsibilities() {
        assertThat(fieldTypes(ProjectApplicationService.class)).contains(
                ProjectImportApplicationService.class, ContentGenerationApplicationService.class);
    }

    @Test
    void jobWorkerShouldUseRegisteredHandlers() {
        assertThat(fieldTypes(DurableJobWorker.class)).doesNotContain(
                ProjectApplicationService.class, PublishingApplicationService.class);
        assertThat(Arrays.stream(DurableJobWorker.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getGenericParameterTypes()))
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .anyMatch(type -> type.getRawType() == List.class
                        && Arrays.asList(type.getActualTypeArguments()).contains(JobHandler.class))).isTrue();
    }

    @Test
    void persistencePortsShouldBeOwnedByFocusedAdapters() {
        assertThat(interfaceTypes(JpaProjectPersistenceAdapter.class)).containsExactlyInAnyOrder(
                ProjectRepository.class, RepositorySnapshotStore.class);
        assertThat(interfaceTypes(JpaArticlePersistenceAdapter.class)).containsExactly(ArticleRepository.class);
        assertThat(interfaceTypes(JpaJobPersistenceAdapter.class)).containsExactly(JobRepository.class);
        assertThat(interfaceTypes(JpaPublishingPersistenceAdapter.class)).containsExactlyInAnyOrder(
                ChannelAccountRepository.class, PublicationRepository.class, ManualPublicationRepository.class);
        assertThat(interfaceTypes(JpaManualChannelProfilePersistenceAdapter.class))
                .containsExactly(ManualChannelProfileRepository.class);
        assertThat(interfaceTypes(JpaAiProviderSettingsPersistenceAdapter.class))
                .containsExactly(AiProviderSettingsRepository.class);
        assertThat(interfaceTypes(JpaAuditRecorder.class)).containsExactly(AuditRecorder.class);
        assertThatThrownBy(() -> Class.forName(
                "io.contentpublisher.platform.infrastructure.persistence.JpaPublisherPersistenceAdapter"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void portalResponsibilitiesShouldRemainSplit() {
        assertThat(requestMethodCount(ContentCreationPortalController.class)).isEqualTo(7);
        assertThat(requestMethodCount(ContentLibraryPortalController.class)).isEqualTo(6);
        assertThat(requestMethodCount(JobPortalController.class)).isEqualTo(3);
        assertThat(requestMethodCount(RecycleBinPortalController.class)).isEqualTo(5);
        assertThatThrownBy(() -> Class.forName(
                "io.contentpublisher.platform.web.controller.PortalManagementController"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void channelCredentialDefinitionsShouldBeCentralizedAndRenderable() {
        assertThat(ChannelCatalog.automated()).allSatisfy(definition -> {
            assertThat(definition.credentialFields()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
            assertThat(definition.credentialKeys()).doesNotHaveDuplicates();
            assertThat(definition.credentialLabelsAttribute()).isNotBlank();
        });
        assertThat(ChannelCatalog.manualOnly()).allSatisfy(definition ->
                assertThat(definition.credentialFields()).isEmpty());
    }

    private Set<Class<?>> fieldTypes(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getType).collect(Collectors.toSet());
    }

    private List<Class<?>> interfaceTypes(Class<?> type) {
        return Arrays.asList(type.getInterfaces());
    }

    private long requestMethodCount(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(this::isRequestMethod).count();
    }

    private boolean isRequestMethod(Method method) {
        return method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class);
    }
}
