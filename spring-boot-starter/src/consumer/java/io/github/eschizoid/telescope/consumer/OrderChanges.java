package io.github.eschizoid.telescope.consumer;

import java.util.List;

/** A PATCH of an order: a field the client leaves out is null. */
public record OrderChanges(String delivery, List<LineDto> lines) {}
