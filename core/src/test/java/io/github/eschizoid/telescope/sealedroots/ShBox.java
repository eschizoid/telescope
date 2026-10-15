package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Holds a case directly, so the processor derives a long-form bridge for the case's pair. */
@Bridge(ShBoxDto.class)
public record ShBox(ShC c) {}
