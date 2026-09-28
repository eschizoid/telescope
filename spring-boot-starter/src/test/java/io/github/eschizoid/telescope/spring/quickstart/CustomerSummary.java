package io.github.eschizoid.telescope.spring.quickstart;

import java.time.Instant;

public record CustomerSummary(String displayName, String source, Instant generatedAt) {}
