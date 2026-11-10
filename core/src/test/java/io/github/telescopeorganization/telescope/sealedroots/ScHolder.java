package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(ScHolderDto.class)
public final class ScHolder implements ScNode {

  private ScSrc back;

  public ScSrc getBack() {
    return back;
  }

  public void setBack(final ScSrc back) {
    this.back = back;
  }
}
