package io.github.eschizoid.telescope.codegen.ctorbox;

import java.util.ArrayList;

/**
 * A public list subtype whose no-argument constructor is protected, which only this package can
 * call and a bridge generated into another package cannot.
 *
 * @param <E> the element type
 */
public class ProtectedBag<E> extends ArrayList<E> {

  private static final long serialVersionUID = 1L;

  protected ProtectedBag() {}
}
