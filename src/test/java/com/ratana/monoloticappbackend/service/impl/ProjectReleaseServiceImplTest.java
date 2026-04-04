package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.model.ProjectRelease;
import com.ratana.monoloticappbackend.repository.ProjectReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectReleaseServiceImplTest {

    private static final String PROJECT_ID = "project-1";

    @Mock
    private ProjectReleaseRepository releaseRepository;

    private ProjectReleaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProjectReleaseServiceImpl(releaseRepository);
    }

    @Test
    void createReleaseSnapshot_keepsOnlyLatestThreeReleases() {
        Project project = createProject();
        Map<String, ProjectRelease> stored = new LinkedHashMap<>();

        mockRepositoryState(stored);

        ProjectRelease release1 = service.createReleaseSnapshot(
                project,
                "DEPLOY",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "tag-1",
                3000,
                "nextjs",
                null,
                "sha-1",
                11
        );
        ProjectRelease release2 = service.createReleaseSnapshot(
                project,
                "DEPLOY",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "tag-2",
                3000,
                "nextjs",
                null,
                "sha-2",
                12
        );
        ProjectRelease release3 = service.createReleaseSnapshot(
                project,
                "DEPLOY",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "tag-3",
                3000,
                "nextjs",
                null,
                "sha-3",
                13
        );
        ProjectRelease release4 = service.createReleaseSnapshot(
                project,
                "DEPLOY",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "tag-4",
                3000,
                "nextjs",
                null,
                "sha-4",
                14
        );

        assertThat(release1.getVersionNumber()).isEqualTo(1L);
        assertThat(release2.getVersionNumber()).isEqualTo(2L);
        assertThat(release3.getVersionNumber()).isEqualTo(3L);
        assertThat(release4.getVersionNumber()).isEqualTo(4L);
        assertThat(release4.getVersionName()).isEqualTo("v1.0.4");
        assertThat(release4.getTriggerType()).isEqualTo("DEPLOY");
        assertThat(release4.getStatus()).isEqualTo("PENDING");
        assertThat(stored).hasSize(3);
        assertThat(stored.values())
                .extracting(ProjectRelease::getVersionNumber)
                .containsExactly(2L, 3L, 4L);
        verify(releaseRepository, times(1)).delete(argThat(release -> release.getVersionNumber().equals(1L)));
    }

    @Test
    void createRollbackSnapshot_copiesReleaseMetadataAndAdvancesVersion() {
        Project project = createProject();
        ProjectRelease sourceRelease = createRelease(
                "release-source",
                PROJECT_ID,
                7L,
                "v1.0.7",
                "source-tag",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "main",
                "springboot-gradle",
                8080,
                "demo.tochratana.com",
                "https://demo.tochratana.com",
                "https://github.com/tochratana/app.git",
                "tochratana/app",
                "DEPLOY",
                "DEPLOYED",
                "ok",
                "sha-7"
        );

        Map<String, ProjectRelease> stored = new LinkedHashMap<>();
        stored.put(sourceRelease.getId(), sourceRelease);
        mockRepositoryState(stored);

        ProjectRelease rollbackRelease = service.createRollbackSnapshot(project, sourceRelease, "source-tag");

        assertThat(rollbackRelease.getVersionNumber()).isEqualTo(8L);
        assertThat(rollbackRelease.getVersionName()).isEqualTo("v1.0.8");
        assertThat(rollbackRelease.getTriggerType()).isEqualTo("ROLLBACK");
        assertThat(rollbackRelease.getRollbackFromReleaseId()).isEqualTo(sourceRelease.getId());
        assertThat(rollbackRelease.getVersionTag()).isEqualTo("source-tag");
        assertThat(rollbackRelease.getImageRepository()).isEqualTo(sourceRelease.getImageRepository());
        assertThat(rollbackRelease.getFramework()).isEqualTo("springboot-gradle");
        assertThat(rollbackRelease.getAppPort()).isEqualTo(8080);
        assertThat(rollbackRelease.getCustomDomain()).isEqualTo("demo.tochratana.com");
        assertThat(rollbackRelease.getCommitSha()).isEqualTo("sha-7");
        assertThat(stored).hasSize(2);
        assertThat(stored.values())
                .extracting(ProjectRelease::getVersionNumber)
                .containsExactly(7L, 8L);
    }

    @Test
    void markCompleted_normalizesSpringBootPortAndMarksReleaseDeployed() {
        ProjectRelease release = createRelease(
                "release-1",
                PROJECT_ID,
                3L,
                "v1.0.3",
                "tag-3",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "main",
                "springboot-gradle",
                3000,
                null,
                "https://tochratana.com",
                "https://github.com/tochratana/app.git",
                "tochratana/app",
                "DEPLOY",
                "PENDING",
                "requested",
                "sha-3"
        );

        when(releaseRepository.findByProjectIdAndId(PROJECT_ID, release.getId())).thenReturn(Optional.of(release));
        when(releaseRepository.save(any(ProjectRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(releaseRepository.findByProjectIdOrderByVersionNumberAsc(PROJECT_ID)).thenReturn(List.of(release));

        ProjectRelease saved = service.markCompleted(PROJECT_ID, release.getId(), 42, "springboot-gradle");

        assertThat(saved.getStatus()).isEqualTo("DEPLOYED");
        assertThat(saved.getStatusMessage()).isEqualTo("Deployment completed successfully");
        assertThat(saved.getBuildNumber()).isEqualTo(42);
        assertThat(saved.getFramework()).isEqualTo("springboot-gradle");
        assertThat(saved.getAppPort()).isEqualTo(8080);
        assertThat(saved.getDeployedAt()).isNotNull();
        verify(releaseRepository).save(release);
    }

    @Test
    void markFailed_normalizesSpringBootPortAndStoresFailureMessage() {
        ProjectRelease release = createRelease(
                "release-2",
                PROJECT_ID,
                4L,
                "v1.0.4",
                "tag-4",
                "harbor.devith.it.com/deployment-pipeline/159990218/tochratana-app",
                "main",
                "springboot-gradle",
                3000,
                null,
                "https://tochratana.com",
                "https://github.com/tochratana/app.git",
                "tochratana/app",
                "DEPLOY",
                "PENDING",
                "requested",
                "sha-4"
        );

        when(releaseRepository.findByProjectIdAndId(PROJECT_ID, release.getId())).thenReturn(Optional.of(release));
        when(releaseRepository.save(any(ProjectRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(releaseRepository.findByProjectIdOrderByVersionNumberAsc(PROJECT_ID)).thenReturn(List.of(release));

        ProjectRelease saved = service.markFailed(PROJECT_ID, release.getId(), 43, "springboot-gradle", "Jenkins trigger failed");

        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getStatusMessage()).isEqualTo("Jenkins trigger failed");
        assertThat(saved.getBuildNumber()).isEqualTo(43);
        assertThat(saved.getFramework()).isEqualTo("springboot-gradle");
        assertThat(saved.getAppPort()).isEqualTo(8080);
        verify(releaseRepository).save(release);
    }

    private void mockRepositoryState(Map<String, ProjectRelease> stored) {
        when(releaseRepository.save(any(ProjectRelease.class))).thenAnswer(invocation -> {
            ProjectRelease release = invocation.getArgument(0);
            if (release.getId() == null || release.getId().isBlank()) {
                release.setId(UUID.randomUUID().toString());
            }
            stored.put(release.getId(), release);
            return release;
        });

        when(releaseRepository.findFirstByProjectIdOrderByVersionNumberDesc(PROJECT_ID)).thenAnswer(invocation ->
                stored.values().stream()
                        .filter(release -> PROJECT_ID.equals(release.getProjectId()))
                        .max(Comparator.comparingLong(ProjectRelease::getVersionNumber))
        );

        when(releaseRepository.findByProjectIdOrderByVersionNumberAsc(PROJECT_ID)).thenAnswer(invocation ->
                stored.values().stream()
                        .filter(release -> PROJECT_ID.equals(release.getProjectId()))
                        .sorted(Comparator.comparingLong(ProjectRelease::getVersionNumber))
                        .collect(Collectors.toCollection(ArrayList::new))
        );

        lenient().doAnswer(invocation -> {
            ProjectRelease release = invocation.getArgument(0);
            if (release != null) {
                stored.remove(release.getId());
            }
            return null;
        }).when(releaseRepository).delete(any(ProjectRelease.class));
    }

    private Project createProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId("159990218");
        project.setWorkspaceId("ws-159990218");
        project.setWorkspaceSlug("ws-159990218");
        project.setAppName("tochratana-app");
        project.setBranch("main");
        project.setRepoUrl("https://github.com/tochratana/app.git");
        project.setRepoFullName("tochratana/app");
        project.setUrl("https://tochratana-app-ws-159990218.tochratana.com");
        project.setFramework("springboot-gradle");
        project.setStatus("BUILDING");
        project.setAppPort(8080);
        project.setCustomDomain("demo.tochratana.com");
        return project;
    }

    private ProjectRelease createRelease(
            String id,
            String projectId,
            Long versionNumber,
            String versionName,
            String versionTag,
            String imageRepository,
            String branch,
            String framework,
            Integer appPort,
            String customDomain,
            String url,
            String repoUrl,
            String repoFullName,
            String triggerType,
            String status,
            String statusMessage,
            String commitSha
    ) {
        ProjectRelease release = new ProjectRelease();
        release.setId(id);
        release.setProjectId(projectId);
        release.setUserId("159990218");
        release.setWorkspaceId("ws-159990218");
        release.setWorkspaceSlug("ws-159990218");
        release.setAppName("tochratana-app");
        release.setVersionNumber(versionNumber);
        release.setVersionName(versionName);
        release.setVersionTag(versionTag);
        release.setImageRepository(imageRepository);
        release.setBranch(branch);
        release.setFramework(framework);
        release.setAppPort(appPort);
        release.setCustomDomain(customDomain);
        release.setUrl(url);
        release.setRepoUrl(repoUrl);
        release.setRepoFullName(repoFullName);
        release.setTriggerType(triggerType);
        release.setStatus(status);
        release.setStatusMessage(statusMessage);
        release.setCommitSha(commitSha);
        release.setCreatedAt(Instant.now());
        release.setUpdatedAt(Instant.now());
        return release;
    }
}
