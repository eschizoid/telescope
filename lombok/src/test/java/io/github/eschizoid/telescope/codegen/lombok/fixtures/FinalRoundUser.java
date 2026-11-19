package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Fixture: a {@code @BeanFocus} class whose accessors Lombok adds through annotations
 * telescope-lombok's processor does not take, so the {@code @BeanFocus} processor writes its
 * navigator in the final round. {@link FinalRoundUserCaller} calls that navigator from a method
 * body in the same compilation.
 */
@BeanFocus
@Getter
@Setter
@NoArgsConstructor
public class FinalRoundUser {

  private String name;
  private int age;
}
