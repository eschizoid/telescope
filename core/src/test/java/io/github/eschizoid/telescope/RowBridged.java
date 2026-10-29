package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * {@link RowRenamed}'s shape, bridged to {@link RowNamedSub} by renames onto the properties the
 * subclass inherits, so the generated bridge can be compared with runtime rows naming the same
 * correspondence.
 */
public record RowBridged(String label, String key, String tag) {
  @Bridge
  public static final Mapper<RowBridged, RowNamedSub> SUB = mapper(
    RowBridged.class,
    RowNamedSub.class,
    to(RowBridged::label, RowNamedSub::getName),
    to(RowBridged::key, RowNamedSub::getCode)
  );
}
