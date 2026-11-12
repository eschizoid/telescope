package io.github.eschizoid.telescope.demo.nativeimage;

import io.github.eschizoid.telescope.demo.api.NewLineV2;
import java.util.List;

/** A restocking batch from the warehouse app, which still sends items in the version 2 shape. */
public record Batch(String id, List<NewLineV2> items) {}
