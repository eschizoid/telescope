package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.List;
import java.util.Set;

public record CartForm(
  Long id,
  Integer version,
  String name,
  List<NoteForm> notes,
  List<StepForm> steps,
  Set<LabelForm> tags,
  List<PinForm> prims
) {}
