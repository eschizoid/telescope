package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import java.util.function.Function;

/**
 * Permit of {@link MapExtractStep} — one {@code Telescope.fromMap(...)} row whose key must carry a
 * value. Users construct via {@link MapExtractStep#required(String, Accessor, Function)}; this
 * record is the carrier, shaped exactly like {@link Extract} because the row says the same three
 * things. Which record it is, is the whole of the difference: a row that names a value the source
 * has to supply cannot be told apart from one that does not by any field it holds.
 */
public record Require<T, X>(
  String key,
  Accessor<T, X> targetAccessor,
  Function<Object, X> converter
) implements MapExtractStep {}
