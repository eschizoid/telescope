package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

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
