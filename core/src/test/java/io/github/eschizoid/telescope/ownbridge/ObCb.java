package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** The other side, whose own bridge upper-cases its label. */
@Bridge(value = ObCbDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
public class ObCb {

  private String label;
  private ObCa a;

  public ObCb() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public ObCa getA() {
    return a;
  }

  public void setA(final ObCa a) {
    this.a = a;
  }
}
