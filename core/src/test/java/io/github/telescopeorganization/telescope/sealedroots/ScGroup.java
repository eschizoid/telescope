package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(ScGroupDto.class)
public final class ScGroup implements ScShape {

  private ScNode node;

  public ScNode getNode() {
    return node;
  }

  public void setNode(final ScNode node) {
    this.node = node;
  }
}
