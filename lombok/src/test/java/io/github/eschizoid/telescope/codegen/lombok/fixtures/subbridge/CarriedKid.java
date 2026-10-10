package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fixture: a {@code @Data} child whose bridge is declared on a carrier in another package. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CarriedKid {

  private String name;
}
