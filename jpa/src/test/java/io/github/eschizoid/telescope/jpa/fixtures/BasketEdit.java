package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;
import java.util.Set;

/** The same shape as {@link BasketForm}, which the runtime mappers convert to and from. */
public record BasketEdit(Long id, String name, List<LineEdit> lines, Set<TagEdit> tags) {}
