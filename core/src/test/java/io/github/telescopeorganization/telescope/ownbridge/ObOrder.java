package io.github.telescopeorganization.telescope.ownbridge;

import java.util.List;

public record ObOrder(String id, ObLine line, List<ObLine> lines) {}
