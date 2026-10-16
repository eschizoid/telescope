package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} bean whose getters and setters Lombok synthesizes for {@code firstLo}
 * and {@code secondLo}, declared in that order. {@link OrderedLombokNames} names the same methods
 * the other way round.
 */
@Data
@NoArgsConstructor
public class OrderedLombokTarget {

  private String firstLo;
  private String secondLo;
}
