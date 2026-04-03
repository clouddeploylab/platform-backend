package com.ratana.monoloticappbackend.service;

import com.ratana.monoloticappbackend.model.Workspace;

public interface WorkspaceService {
    Workspace ensurePersonalWorkspace(String userId, String preferredName);
}
