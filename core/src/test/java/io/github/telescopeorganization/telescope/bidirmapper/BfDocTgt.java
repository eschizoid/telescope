package io.github.telescopeorganization.telescope.bidirmapper;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;

@BeanFocus
public class BfDocTgt {

  private Boolean numberOfAttempts;

  public Boolean getNumberOfAttempts() {
    return numberOfAttempts;
  }

  public void setNumberOfAttempts(final Boolean v) {
    this.numberOfAttempts = v;
  }
}
