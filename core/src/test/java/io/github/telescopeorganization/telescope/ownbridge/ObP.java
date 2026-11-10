package io.github.telescopeorganization.telescope.ownbridge;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.List;

@Bridge(ObPDto.class)
public record ObP(List<ObX> xs) {}
