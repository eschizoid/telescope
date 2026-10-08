package io.github.eschizoid.telescope.codegen.ctorbox;

import java.util.ArrayList;

/**
 * A public list subtype whose no-argument constructor only this package can call, which a bridge
 * generated into another package cannot.
 *
 * @param <E> the element type
 */
public class PackageBag<E> extends ArrayList<E> {

  private static final long serialVersionUID = 1L;

  PackageBag() {}
}
