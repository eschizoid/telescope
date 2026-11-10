package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/**
 * Bean (getter/setter) parent holding a raw collection-subtype field — the adopter's @Data shape.
 */
@Bridge(RawColBeanDst.class)
public class RawColBeanSrc {

  private RawColSrcWrap items;

  public RawColSrcWrap getItems() {
    return items;
  }

  public void setItems(final RawColSrcWrap items) {
    this.items = items;
  }
}
