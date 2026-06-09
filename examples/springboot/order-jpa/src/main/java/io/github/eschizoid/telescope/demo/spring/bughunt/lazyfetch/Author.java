package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import io.github.eschizoid.telescope.annotations.Focus;

/** Domain-side author record. Mirrors {@link AuthorEntity}'s shape. */
@Focus
public record Author(Long id, String name) {}
