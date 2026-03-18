package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.dto.RepoDto;
import com.ratana.monoloticappbackend.model.GitHubToken;
import com.ratana.monoloticappbackend.repository.GitHubTokenRepository;
import com.ratana.monoloticappbackend.service.GitHubService;
import com.ratana.monoloticappbackend.service.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubServiceImpl implements GitHubService {

    private final GitHubTokenRepository tokenRepo;
    private final TokenEncryptionService encryptionService;

    private final WebClient githubClient = WebClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    @Override
    public List<RepoDto> getReposForUser(String userId) {
        GitHubToken tokenEntity = tokenRepo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No GitHub token found for user: " + userId));

        String plainToken;
        try {
            plainToken = encryptionService.decrypt(tokenEntity.getEncryptedToken());
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt GitHub token for user: " + userId, e);
        }

        // Fetch repos in smaller pages to avoid hitting default WebClient buffer limits.
        final int perPage = 30;
        int page = 1;
        List<Map<String, Object>> allRepos = new ArrayList<>();

        while (true) {
            final int currentPage = page;
            List<Map<String, Object>> pageRepos = githubClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/user/repos")
                            .queryParam("per_page", perPage)
                            .queryParam("page", currentPage)
                            .queryParam("sort", "updated")
                            .queryParam("affiliation", "owner,collaborator")
                            .build())
                    .header("Authorization", "Bearer " + plainToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block();

            if (pageRepos == null || pageRepos.isEmpty()) {
                break;
            }

            allRepos.addAll(pageRepos);

            if (pageRepos.size() < perPage) {
                break;
            }
            page++;
        }

        return allRepos.stream()
                .map(r -> new RepoDto(
                        (String) r.get("full_name"),
                        (String) r.get("clone_url"),
                        (String) r.get("default_branch"),
                        Boolean.TRUE.equals(r.get("private")),
                        (String) r.get("description")
                ))
                .collect(Collectors.toList());
    }
}
