package com.ratana.monoloticappbackend.repository;

import com.ratana.monoloticappbackend.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findByUserId(String userId);
    List<Project> findByWorkspaceId(String workspaceId);
    long countByWorkspaceId(String workspaceId);
    Optional<Project> findByAppName(String appName);
    boolean existsByAppName(String appName);
    boolean existsByWorkspaceIdAndAppName(String workspaceId, String appName);
    Optional<Project> findByUserIdAndId(String userId, String id);
    Optional<Project> findByRepoProviderAndRepoFullName(String repoProvider, String repoFullName);
    Optional<Project> findByRepoProviderIgnoreCaseAndRepoFullNameIgnoreCase(String repoProvider, String repoFullName);

}
