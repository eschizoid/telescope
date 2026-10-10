package io.github.eschizoid.telescope.frommapparity;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One component of every shape a map value can be converted into, read by its own name. */
@FromMap
public record BackfillRow(
  int i,
  long l,
  double d,
  float f,
  short s,
  byte b,
  boolean flag,
  char c,
  Integer boxedInt,
  Long boxedLong,
  Double boxedDouble,
  Float boxedFloat,
  Short boxedShort,
  Byte boxedByte,
  Boolean boxedFlag,
  Character boxedChar,
  String text,
  CharSequence chars,
  Object opaque,
  BackfillTone tone,
  Instant when,
  BigDecimal amount,
  BackfillLeaf leaf,
  List<BackfillLeaf> leaves,
  List<Integer> numbers,
  Set<String> tags,
  Map<String, Integer> counts,
  Map<String, List<BackfillTone>> tonesByKey,
  Optional<String> maybe,
  Optional<BackfillLeaf> maybeLeaf,
  Optional<List<Long>> maybeLongs
) {}
