package io.github.telescopeorganization.telescope.ownbridge;

import java.util.List;

public record ObOrderDto(String id, ObLineDto line, List<ObLineDto> lines) {}
