package io.github.eschizoid.telescope.collections;

import java.util.List;

/** An order as a client sends it. */
public record ROrderDto(String name, List<RLineDto> lines) {}
