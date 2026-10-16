package io.github.eschizoid.telescope.sealedroots;

public final class ScHolderDto implements ScNodeDto {

  private ScTgt back;

  public ScTgt getBack() {
    return back;
  }

  public void setBack(final ScTgt back) {
    this.back = back;
  }
}
