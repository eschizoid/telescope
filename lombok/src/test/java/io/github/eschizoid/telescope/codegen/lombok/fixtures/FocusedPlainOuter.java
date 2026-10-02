package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Fixture: {@code @BeanFocus} alone, holding a {@code @BeanFocus} child and a {@code @Data} child.
 * Its navigator descends into both, as {@link FocusedDataOuter}'s does.
 */
@BeanFocus
public class FocusedPlainOuter {

  private FocusedChild child;
  private DataUser user;

  public FocusedPlainOuter() {}

  public FocusedChild getChild() {
    return child;
  }

  public void setChild(final FocusedChild child) {
    this.child = child;
  }

  public DataUser getUser() {
    return user;
  }

  public void setUser(final DataUser user) {
    this.user = user;
  }
}
