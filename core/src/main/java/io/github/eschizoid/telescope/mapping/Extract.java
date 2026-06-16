package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import java.util.function.Function;

/**
 * Permit of {@link MapExtractStep} — one {@code Telescope.fromMap(...)} row. Users construct via
 * {@link MapExtractStep#extract(String, Accessor, Function)}; this record is the package-private
 * carrier. The record-generated accessors return {@code Accessor<T, X>} / {@code Function<Object,
 * X>}, which are covariant with the {@link MapExtractStep} supertype's wildcarded returns.
 */
public record Extract<T, X>(
  String key,
  Accessor<T, X> targetAccessor,
  Function<Object, X> converter
) implements MapExtractStep {}
