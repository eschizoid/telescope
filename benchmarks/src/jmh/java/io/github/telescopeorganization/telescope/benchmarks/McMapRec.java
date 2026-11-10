package io.github.telescopeorganization.telescope.benchmarks;

import java.util.Map;

/** Map-valued container tier, target side. Mirror of {@link McMapBean}. */
public record McMapRec(String name, Map<String, McTeamRec> teams) {}
