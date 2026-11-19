package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

public record RowEdit(Long id, String text, List<CellEdit> subs) {}
