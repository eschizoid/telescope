package io.github.telescopeorganization.telescope.sealedroots;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SrDrawingDto(
  String title,
  SrShapeDto main,
  List<SrShapeDto> shapes,
  Optional<SrShapeDto> spare,
  Map<String, SrShapeDto> named
) {}
