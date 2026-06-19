package io.github.eschizoid.telescope.focus;

/**
 * Leaf of an UN-annotated {@code root → outer → mid → leaf} POJO chain. Mirrors {@link
 * WriteChainLeaf} but without {@code @BeanFocus}, so the lens for each hop is built by {@link
 * io.github.eschizoid.telescope.internal.Beans Beans.lens} rather than the codegen holder lens.
 * Used to pin null-intermediate auto-construction on the reflective bean path.
 */
public class PlainChainLeaf {

  private String value;

  public PlainChainLeaf() {}

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
