package com.ratana.monoloticappbackend.repository;

import com.ratana.monoloticappbackend.model.GitHubToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitHubTokenRepository extends JpaRepository<GitHubToken, String> {
    Optional<GitHubToken> findByUserId(String userId);
}
