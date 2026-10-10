package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Fixture: target side of {@link LoopKid}. */
@Data
@NoArgsConstructor
public class LoopKidDto {

  private String name;
  private LoopParentDto parent;
}
