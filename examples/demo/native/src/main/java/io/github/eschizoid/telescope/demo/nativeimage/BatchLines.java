package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.demo.api.NewLine;
import java.util.List;

/** A restocking batch in the shape the service works with. */
public record BatchLines(String id, List<NewLine> items) {}
