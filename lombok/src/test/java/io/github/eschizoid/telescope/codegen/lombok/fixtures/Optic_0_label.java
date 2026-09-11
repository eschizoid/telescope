package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: an outer class whose name is what the nested class's per-field holder would otherwise be
 * called. The holder is a member type of the navigator, so taking that name would shadow the outer
 * segment of the navigated type's reference throughout the emitted file — including the class
 * header, which names {@code Optic_0_label.Inner}.
 *
 * <p>Nested sources reach this only through Lombok: {@code @Focus} and {@code @BeanFocus} both
 * reject a nested type at the gate, so no in-memory harness case can cover it.
 */
public final class Optic_0_label {

  private Optic_0_label() {}

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Inner {

    private String label;
  }
}
