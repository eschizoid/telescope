package io.github.eschizoid.telescope.collections;

import java.util.List;

/** An order as a client sends it. */
public record WOrderDto(String name, List<WLineDto> lines) {}
