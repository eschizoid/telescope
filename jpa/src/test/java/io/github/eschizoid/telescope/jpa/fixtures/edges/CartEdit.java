package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;
import java.util.Set;

public record CartEdit(
  Long id,
  Integer version,
  String name,
  List<NoteEdit> notes,
  List<StepEdit> steps,
  Set<LabelEdit> tags,
  List<PinEdit> prims
) {}
