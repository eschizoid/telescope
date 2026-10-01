package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * Fixture: a bean whose builder and all-args constructor Lombok synthesises while its getter and
 * setter are written by hand. The setter tags what it writes, so a rebuild through it is visible in
 * the value; the builder comes first in the auto order, and only a processor that reads the bean
 * after Lombok has added {@code builder()} can see it.
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltLombokUser {

  private String name;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name + "[setters]";
  }
}
