package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Sealed values in a field and inside each container family. */
@Bridge(SrDrawingDto.class)
public record SrDrawing(
  String title,
  SrShape main,
  List<SrShape> shapes,
  Optional<SrShape> spare,
  Map<String, SrShape> named
) {}
