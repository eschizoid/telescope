package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.Builder;
import lombok.Value;

/**
 * Fixture: a {@code @Value @Builder} with a field given its value where it is declared, which
 * Lombok's all-args constructor and builder leave out. Nothing writes that field, so the builder
 * still carries the bean; its navigator has to be emitted in time for this compilation's own test
 * sources to name it.
 */
@Value
@Builder
public class KindValue {

  String name;
  String kind = "K";
}
