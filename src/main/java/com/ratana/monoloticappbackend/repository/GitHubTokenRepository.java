package com.ratana.monoloticappbackend.repository;

import com.ratana.monoloticappbackend.model.GitHubToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubTokenRepository extends JpaRepository<GitHubToken, String> {
    Integer findByUserId(String userId);
}
