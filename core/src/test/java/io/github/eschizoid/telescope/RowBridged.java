package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;

/**
 * {@link RowRenamed}'s shape, bridged to {@link RowNamedSub} by renames onto the properties the
 * subclass inherits, so the generated bridge can be compared with runtime rows naming the same
 * correspondence.
 */
@Bridge(
  value = RowNamedSub.class,
  renames = { @Rename(source = "label", target = "name"), @Rename(source = "key", target = "code") }
)
public record RowBridged(String label, String key, String tag) {}
