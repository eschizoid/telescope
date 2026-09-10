package io.github.eschizoid.telescope.benchmarks;

import java.util.Set;

/** Set-valued container tier, target side. Mirror of {@link McSetBean}. */
public record McSetRec(String name, Set<McTeamRec> teams) {}
