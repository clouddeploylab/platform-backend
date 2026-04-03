package com.ratana.monoloticappbackend.repository;

import com.ratana.monoloticappbackend.model.ProjectRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectReleaseRepository extends JpaRepository<ProjectRelease, String> {
    List<ProjectRelease> findByProjectIdOrderByVersionNumberDesc(String projectId);
    List<ProjectRelease> findByProjectIdOrderByVersionNumberAsc(String projectId);
    Optional<ProjectRelease> findFirstByProjectIdOrderByVersionNumberDesc(String projectId);
    Optional<ProjectRelease> findByProjectIdAndId(String projectId, String id);
}
