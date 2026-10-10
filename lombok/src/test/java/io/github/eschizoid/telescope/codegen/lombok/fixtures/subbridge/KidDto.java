package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fixture: target side of the auto-derived {@link Kid} pair. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KidDto {

  private String name;
  private int age;
}
