package io.github.telescopeorganization.telescope.codegen.lombok.fixtures;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;

/** Fixture: a plain {@code @BeanFocus} bean, nested in the outer fixtures below. */
@BeanFocus
public class FocusedChild {

  private String name;

  public FocusedChild() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
