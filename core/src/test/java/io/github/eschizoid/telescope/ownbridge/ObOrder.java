package io.github.eschizoid.telescope.ownbridge;

import java.util.List;

public record ObOrder(String id, ObLine line, List<ObLine> lines) {}
