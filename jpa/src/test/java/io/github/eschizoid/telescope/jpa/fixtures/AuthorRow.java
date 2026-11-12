package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/**
 * An author as a service hands it on, holding the books as the entity declares them. The runtime
 * mapper converts to it.
 */
public record AuthorRow(Long id, String name, List<Book> books) {}
