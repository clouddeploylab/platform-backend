package com.ratana.monoloticappbackend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "github_tokens")
public class GitHubToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String userId;          // GitHub user ID

    @Column(nullable = false)
    private String encryptedToken;  // AES-256-GCM ciphertext

    @Column(nullable = false)
    private String iv;              // initialization vector (Base64)

    private Instant createdAt;
    private Instant updatedAt;
    // getters/setters omitted for brevity
}
