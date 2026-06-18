package io.github.eschizoid.telescope.focus;

import java.util.Optional;

/**
 * Source POJO with an {@link Optional} field; pairs with a target whose mapping reads via {@code
 * .whenPresent}.
 */
public class OptionalSourceBean {

  private Optional<String> maybeName = Optional.empty();

  public OptionalSourceBean() {}

  public Optional<String> getMaybeName() {
    return maybeName;
  }

  public void setMaybeName(final Optional<String> maybeName) {
    this.maybeName = maybeName;
  }
}
