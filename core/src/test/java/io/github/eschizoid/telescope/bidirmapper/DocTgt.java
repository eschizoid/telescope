package io.github.eschizoid.telescope.bidirmapper;

/** Target: numberOfAttempts is a String — renamed AND type-mismatched vs the source's Integer. */
public class DocTgt {

  private String numberOfAttempts;

  public DocTgt() {}

  public String getNumberOfAttempts() {
    return numberOfAttempts;
  }

  public void setNumberOfAttempts(final String v) {
    this.numberOfAttempts = v;
  }
}
