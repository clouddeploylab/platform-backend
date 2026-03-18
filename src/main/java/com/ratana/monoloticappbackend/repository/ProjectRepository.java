package com.ratana.monoloticappbackend.repository;

import com.ratana.monoloticappbackend.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findByUserId(String userId);
    Optional<Project> findByAppName(String appName);
    boolean existsByAppName(String appName);
}
