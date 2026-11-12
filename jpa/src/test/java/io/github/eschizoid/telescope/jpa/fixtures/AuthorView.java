package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/**
 * The same shape as {@link AuthorRow}, converted to by the bridge {@link AuthorMappers#VIEW}
 * generates, so each path has a pair of its own.
 */
public record AuthorView(Long id, String name, List<Book> books) {}
