package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Fixture: a plain record bridge whose field type is the multi-target Lombok child. */
@Bridge(TwinParentDto.class)
public record TwinParent(String id, TwinKid kid) {}
