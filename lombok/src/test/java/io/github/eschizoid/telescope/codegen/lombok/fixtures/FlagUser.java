package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} bean with a primitive boolean named {@code isActive}, whose setter
 * Lombok names {@code setActive}. Its navigator has to be emitted in time for this compilation's
 * own test sources to name it.
 */
@Data
@NoArgsConstructor
public class FlagUser {

  private String id;
  private boolean isActive;
}
