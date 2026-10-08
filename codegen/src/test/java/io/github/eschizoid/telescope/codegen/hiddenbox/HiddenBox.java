package io.github.eschizoid.telescope.codegen.hiddenbox;

import java.util.ArrayList;

/** Not public, so the container nested in it can be named only from this package. */
final class HiddenBox {

  private HiddenBox() {}

  /** A plain list subtype, public itself and reachable through a private lookup. */
  public static class Bag<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    public Bag() {}
  }
}
