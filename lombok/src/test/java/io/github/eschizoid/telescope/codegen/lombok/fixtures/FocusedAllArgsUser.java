package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @BeanFocus} bean whose all-args constructor is synthesised by Lombok while its
 * getter and setter are written by hand. The setter tags what it writes, so a rebuild through the
 * setter is visible in the value. The constructor comes before the setters in the auto order, and
 * only a processor that reads the bean after Lombok has added the constructor can see it.
 */
@BeanFocus
@NoArgsConstructor
@AllArgsConstructor
public class FocusedAllArgsUser {

  private String name;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name + "[setters]";
  }
}
