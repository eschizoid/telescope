package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.Map;
import java.util.Optional;

/** An Optional field, a Map with non-String keys, and a plain Map for the null-value test. */
@FromMap
public record FmExtras(Optional<FmAddress> maybeHq, Map<Integer, String> byCode, Map<String, String> notes) {}
