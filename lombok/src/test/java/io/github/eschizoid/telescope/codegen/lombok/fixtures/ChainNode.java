package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} bean that fixes its superclass's variable to itself, so the inherited
 * {@code next} is a {@code ChainNode} as a member of this class and a {@code T} as declared.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ChainNode extends ChainBase<ChainNode> {

  private String label;
}
