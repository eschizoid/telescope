package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} child with two {@code @Bridge} targets, so neither of its bridges takes
 * the short name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Bridge(TwinKidDto.class)
@Bridge(TwinKidView.class)
public class TwinKid {

  private String name;
}
