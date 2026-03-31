package com.ratana.monoloticappbackend.repository;

import com.ratana.monoloticappbackend.model.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {
    Optional<Workspace> findByOwnerUserId(String ownerUserId);
}
