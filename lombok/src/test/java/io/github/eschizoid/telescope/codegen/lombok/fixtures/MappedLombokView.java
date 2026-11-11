package io.github.eschizoid.telescope.codegen.lombok.fixtures;

/**
 * Fixture: the record {@link MappedLombokUser#VIEW} maps a {@link MappedLombokUser} to, converting
 * back through the {@code MappedLombokViewMappings} interface the field generates.
 */
public record MappedLombokView(String username, String email) implements MappedLombokViewMappings {}
