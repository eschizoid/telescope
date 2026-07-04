package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: outer class holds a nested static {@code @Data} POJO. Pins that {@code
 * LombokFocusProcessor} emits a navigator for the nested class, with the outer's name folded into
 * the generated class name to avoid collision with any top-level sibling of the same simple name.
 *
 * <p>Expected emissions:
 *
 * <ul>
 *   <li>{@code OuterWithNestedInnerTelescope} — at package level (not nested inside
 *       OuterWithNested).
 *   <li>{@code OuterWithNestedInnerFieldOptics} — the metadata holder, also flattened.
 * </ul>
 *
 * The outer class itself is not annotated with a Lombok bean trigger so no navigator is emitted for
 * it.
 */
public class OuterWithNested {

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Inner {

    private String label;
    private int weight;
  }
}
