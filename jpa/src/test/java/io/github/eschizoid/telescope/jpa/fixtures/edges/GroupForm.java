package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

public record GroupForm(Long id, String name, List<RowForm> lines) {}
