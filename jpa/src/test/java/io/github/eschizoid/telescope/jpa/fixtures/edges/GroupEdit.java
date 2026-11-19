package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

public record GroupEdit(Long id, String name, List<RowEdit> lines) {}
