package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

public record RowForm(Long id, String text, List<CellForm> subs) {}
