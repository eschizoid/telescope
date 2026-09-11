package io.github.eschizoid.telescope.bridgexpkg.maporder;

import java.util.Map;
import java.util.Set;

public record MapOrderTarget(Map<String, LeafDto> byKey, Set<LeafDto> items) {}
