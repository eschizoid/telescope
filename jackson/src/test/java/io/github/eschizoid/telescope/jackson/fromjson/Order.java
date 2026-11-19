package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** An order holding every kind of property the binder reads. */
@FromJson
public record Order(
  String number,
  List<Line> lines,
  Status status,
  Optional<String> note,
  Map<String, Integer> counts,
  Set<String> tags,
  Instant placedOn,
  boolean gift,
  long version,
  double weight,
  char grade,
  BigInteger serial,
  Object extra
) {}
