package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Fixture: a plain record bridge whose field type is a Lombok child without a {@code @Bridge}. */
@Bridge(KidParentDto.class)
public record KidParent(String id, Kid kid) {}
