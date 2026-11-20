package io.github.eschizoid.telescope.quarkus.tests.unpaired;

/**
 * A source whose pair with {@link Unrelated} shares no name and is mapped by a {@code @Bridge}
 * field in another package.
 *
 * @param label the badge's label
 */
public record Badge(String label) {}
