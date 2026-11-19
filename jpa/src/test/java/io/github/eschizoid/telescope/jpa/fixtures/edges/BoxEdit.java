package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;

public record BoxEdit(Long id, List<NoteEdit> notes) {}
