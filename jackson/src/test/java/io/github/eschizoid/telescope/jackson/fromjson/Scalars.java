package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;

/** One property of each scalar kind. */
@FromJson
public record Scalars(
  int i,
  long l,
  short s,
  byte b,
  double d,
  float f,
  boolean z,
  char c,
  Integer boxed,
  BigDecimal decimal,
  BigInteger big,
  String text,
  CharSequence chars,
  UUID id,
  Locale locale
) {}
