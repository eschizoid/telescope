package io.github.telescopeorganization.telescope.codegen.lombok.fixtures.subbridge;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Fixture: a plain record bridge on a cycle through an auto-derived Lombok child pair. */
@Bridge(LoopParentDto.class)
public record LoopParent(String id, LoopKid kid) {}
