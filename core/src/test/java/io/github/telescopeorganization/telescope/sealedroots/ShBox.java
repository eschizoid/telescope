package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Holds a case directly, so the processor derives a long-form bridge for the case's pair. */
@Bridge(ShBoxDto.class)
public record ShBox(ShC c) {}
