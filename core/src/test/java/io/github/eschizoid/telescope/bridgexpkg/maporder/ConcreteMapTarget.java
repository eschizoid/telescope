package io.github.eschizoid.telescope.bridgexpkg.maporder;

import java.util.HashMap;
import java.util.LinkedHashMap;

public record ConcreteMapTarget(HashMap<String, LeafDto> hashed, LinkedHashMap<String, LeafDto> ordered) {}
