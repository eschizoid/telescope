package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import lombok.Builder;
import lombok.Value;

/** Fixture: a class carrying both {@code @BeanFocus} and {@code @Value @Builder}. */
@BeanFocus
@Value
@Builder
public class FocusedBuiltValue {

  String name;
  int age;
}
