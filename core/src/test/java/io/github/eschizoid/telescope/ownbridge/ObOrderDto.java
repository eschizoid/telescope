package io.github.eschizoid.telescope.ownbridge;

import java.util.List;

public record ObOrderDto(String id, ObLineDto line, List<ObLineDto> lines) {}
