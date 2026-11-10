package io.github.telescopeorganization.telescope.bridgexpkg.maporder;

import java.util.Map;
import java.util.Set;

public record MapOrderTarget(Map<String, LeafDto> byKey, Set<LeafDto> items) {}
