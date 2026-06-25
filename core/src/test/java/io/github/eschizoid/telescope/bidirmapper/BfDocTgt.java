package io.github.eschizoid.telescope.bidirmapper;

import io.github.eschizoid.telescope.annotations.BeanFocus;

@BeanFocus
public class BfDocTgt {

  private String numberOfAttempts;

  public String getNumberOfAttempts() {
    return numberOfAttempts;
  }

  public void setNumberOfAttempts(final String v) {
    this.numberOfAttempts = v;
  }
}
