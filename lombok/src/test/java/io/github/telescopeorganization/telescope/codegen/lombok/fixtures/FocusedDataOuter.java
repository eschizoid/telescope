package io.github.telescopeorganization.telescope.codegen.lombok.fixtures;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: {@code @BeanFocus @Data}, holding a {@code @BeanFocus} child and a {@code @Data} child.
 * Its navigator descends into both.
 */
@BeanFocus
@Data
@NoArgsConstructor
public class FocusedDataOuter {

  private FocusedChild child;
  private DataUser user;
}
