package com.ratana.monoloticappbackend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "project_releases",
        indexes = {
                @Index(name = "idx_project_releases_project_id", columnList = "projectId"),
                @Index(name = "idx_project_releases_project_id_version_number", columnList = "projectId,versionNumber")
        }
)
public class ProjectRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String projectId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String workspaceId;

    @Column
    private String workspaceSlug;

    @Column(nullable = false)
    private String appName;

    @Column(nullable = false)
    private Long versionNumber;

    @Column(nullable = false)
    private String versionName;

    @Column(nullable = false)
    private String versionTag;

    @Column(nullable = false)
    private String imageRepository;

    @Column(nullable = false)
    private String branch;

    @Column
    private String framework;

    @Column
    private Integer appPort;

    @Column
    private String customDomain;

    @Column
    private String url;

    @Column
    private String repoUrl;

    @Column
    private String repoFullName;

    @Column(nullable = false)
    private String triggerType; // DEPLOY | SYNC | ROLLBACK

    @Column(nullable = false)
    private String status; // PENDING | DEPLOYED | FAILED

    @Column
    private String statusMessage;

    @Column
    private String rollbackFromReleaseId;

    @Column
    private String jenkinsJobName;

    @Column
    private Integer queueItemId;

    @Column
    private Integer buildNumber;

    @Column
    private String commitSha;

    @Column
    private Instant createdAt;

    @Column
    private Instant updatedAt;

    @Column
    private Instant deployedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
