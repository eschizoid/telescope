package io.github.eschizoid.telescope.bidirmapper;

/**
 * Target: numberOfAttempts is a Boolean — renamed, and of a type no conversion is built in for
 * from the source's Integer.
 */
public class DocTgt {

  private Boolean numberOfAttempts;

  public DocTgt() {}

  public Boolean getNumberOfAttempts() {
    return numberOfAttempts;
  }

  public void setNumberOfAttempts(final Boolean v) {
    this.numberOfAttempts = v;
  }
}
