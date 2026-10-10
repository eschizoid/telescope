package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} child with no {@code @Bridge} of its own, so its pair is auto-derived.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Kid {

  private String name;
  private int age;
}
