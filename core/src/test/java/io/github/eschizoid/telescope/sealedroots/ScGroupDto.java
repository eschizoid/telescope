package io.github.eschizoid.telescope.sealedroots;

public final class ScGroupDto implements ScShapeDto {

  private ScNodeDto node;

  public ScNodeDto getNode() {
    return node;
  }

  public void setNode(final ScNodeDto node) {
    this.node = node;
  }
}
