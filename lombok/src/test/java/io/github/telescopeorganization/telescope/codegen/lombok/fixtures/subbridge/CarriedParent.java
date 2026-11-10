package io.github.telescopeorganization.telescope.codegen.lombok.fixtures.subbridge;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Fixture: a plain record bridge whose field type is a Lombok child bridged by a carrier. */
@Bridge(CarriedParentDto.class)
public record CarriedParent(String id, CarriedKid kid) {}
