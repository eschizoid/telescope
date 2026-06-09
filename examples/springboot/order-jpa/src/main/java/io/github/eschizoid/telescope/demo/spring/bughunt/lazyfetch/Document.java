package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import io.github.eschizoid.telescope.annotations.Focus;

/** Domain-side document record. Carries an inline {@link Author} (eagerly resolved on the wire). */
@Focus
public record Document(Long id, String title, Author author) {}
