package io.github.eschizoid.telescope.quarkus.tests.unpaired;

/**
 * A source whose pair with {@link Unrelated} shares no name and is mapped by a {@code Mapper} bean.
 *
 * @param mark the seal's mark
 * @param size the seal's size
 */
public record Seal(String mark, int size) {}
