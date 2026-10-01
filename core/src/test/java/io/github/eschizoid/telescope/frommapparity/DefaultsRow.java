package io.github.eschizoid.telescope.frommapparity;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One component of every shape a missing key can land on. Both binders are built from this record,
 * so a value either of them chooses for an absent key is compared against the other's.
 */
@FromMap
public record DefaultsRow(
  String text,
  Integer boxed,
  Boolean flag,
  BigDecimal amount,
  UUID id,
  Instant when,
  int count,
  boolean truth,
  char letter,
  List<String> items,
  Set<String> tags,
  Map<String, String> meta,
  Optional<String> maybe,
  // An Optional of a container is the shape where the two answers can differ without either
  // looking wrong: an empty Optional and an Optional holding an empty container are both
  // plausible readings of a key that carries nothing, and only one of them can be right on both
  // paths.
  Optional<List<String>> maybeItems,
  Optional<Set<String>> maybeTags,
  Optional<Map<String, String>> maybeMeta,
  // The element here is neither a scalar nor a container, which makes this the one component that
  // tells "the key carried nothing" apart from "the element coercion answered with something
  // empty". A guard written against container elements passes every row above and fails this one.
  Optional<Optional<String>> maybeMaybe
) {}
