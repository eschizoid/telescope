package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.annotations.FromMap;
import io.github.eschizoid.telescope.jackson.Fixtures.Status;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

/** One property of each scalar kind, read by all three binders: the JSON one and both map ones. */
@FromMap
public record StrictScalars(
  byte b,
  short s,
  int i,
  long l,
  float f,
  double d,
  boolean z,
  char c,
  Integer boxed,
  BigInteger big,
  BigDecimal decimal,
  Locale locale,
  String text,
  Status status
) {}
