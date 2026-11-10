package io.github.telescopeorganization.telescope.codegen.lombok.fixtures;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;
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
