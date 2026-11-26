package io.github.eschizoid.telescope.collections;

import java.util.List;

/** A box as a client sends it. */
public record GLineBoxDto(List<GLineDto> items) {}
