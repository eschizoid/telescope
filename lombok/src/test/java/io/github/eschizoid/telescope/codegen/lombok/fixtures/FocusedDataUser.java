package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a class carrying both {@code @BeanFocus} and {@code @Data}. One navigator and one holder
 * are emitted for it, in time for this compilation's own test sources to name them.
 */
@BeanFocus
@Data
@NoArgsConstructor
public class FocusedDataUser {

  private String name;
  private int age;
}
