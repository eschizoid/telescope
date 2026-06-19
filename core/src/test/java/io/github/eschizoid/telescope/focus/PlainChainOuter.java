package io.github.eschizoid.telescope.focus;

/** Outer hop of the UN-annotated {@code root → outer → mid → leaf} POJO chain. */
public class PlainChainOuter {

  private PlainChainMid mid;

  public PlainChainOuter() {}

  public PlainChainMid getMid() {
    return mid;
  }

  public void setMid(final PlainChainMid mid) {
    this.mid = mid;
  }
}
