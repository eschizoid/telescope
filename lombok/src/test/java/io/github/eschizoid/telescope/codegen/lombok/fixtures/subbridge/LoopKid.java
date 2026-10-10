package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} child with no {@code @Bridge} that points back at its parent, so its
 * pair lies on a cycle with the parent's.
 */
@Data
@NoArgsConstructor
public class LoopKid {

  private String name;
  private LoopParent parent;
}
