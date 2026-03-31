package com.ratana.monoloticappbackend.service;

import com.ratana.monoloticappbackend.dto.RepoDto;

import java.util.List;

public interface GitHubService {
    /**
     * Fetch repositories for a user by decrypting their stored GitHub token
     * and calling the GitHub REST API. Never leaks the token to clients.
     */
    List<RepoDto> getReposForUser(String userId);

    /**
     * Fetch branch names for a repository (owner/repo) visible to the user.
     */
    List<String> getBranchesForRepo(String userId, String repoFullName);
}
