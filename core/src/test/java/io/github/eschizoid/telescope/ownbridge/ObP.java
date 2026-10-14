package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;

@Bridge(ObPDto.class)
public record ObP(List<ObX> xs) {}
