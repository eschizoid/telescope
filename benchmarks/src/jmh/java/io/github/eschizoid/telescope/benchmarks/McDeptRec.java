package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/**
 * Deep-tier middle record. Mirror of {@link McDeptBean}: {@code name} + {@code List<McTeamRec>}.
 */
public record McDeptRec(String name, List<McTeamRec> teams) {}
