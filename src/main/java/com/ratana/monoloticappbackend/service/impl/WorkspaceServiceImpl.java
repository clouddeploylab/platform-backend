package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.model.Workspace;
import com.ratana.monoloticappbackend.repository.WorkspaceRepository;
import com.ratana.monoloticappbackend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    @Override
    public Workspace ensurePersonalWorkspace(String userId, String preferredName) {
        return workspaceRepository.findByOwnerUserId(userId)
                .orElseGet(() -> {
                    Workspace workspace = new Workspace();
                    workspace.setOwnerUserId(userId);
                    workspace.setName(resolveWorkspaceName(preferredName));
                    workspace.setSlug(buildSlug(userId));
                    return workspaceRepository.save(workspace);
                });
    }

    private String resolveWorkspaceName(String preferredName) {
        if (preferredName == null || preferredName.isBlank()) {
            return "My Workspace";
        }
        return preferredName;
    }

    private String buildSlug(String userId) {
        String normalized = userId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = "workspace";
        }
        if (normalized.length() > 45) {
            normalized = normalized.substring(0, 45);
        }
        return "ws-" + normalized;
    }
}
