package io.github.eschizoid.telescope.collections;

import java.util.List;

/** An order as a client sends it. */
public record ZOrderDto(String name, List<ZLineDto> lines) {}
