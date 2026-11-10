package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** The root of a cycle that runs through the cases of two sealed fields. */
@Bridge(ScTgt.class)
public class ScSrc {

  private ScShape s;

  public ScShape getS() {
    return s;
  }

  public void setS(final ScShape s) {
    this.s = s;
  }
}
