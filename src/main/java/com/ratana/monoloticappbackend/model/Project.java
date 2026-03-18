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
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;           // GitHub user ID / sub

    @Column(nullable = false, unique = true)
    private String appName;          // slug used as subdomain

    @Column(nullable = false)
    private String repoUrl;          // clone URL

    @Column(nullable = false)
    private String branch;

    private String framework;        // detected: nextjs | springboot | nodejs | …

    @Column(nullable = false)
    private String status;           // BUILDING | DEPLOYED | FAILED

    private String url;              // https://<appName>.yourplatform.com

    private Integer appPort;         // container port

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
