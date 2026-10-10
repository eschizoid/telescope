package io.github.eschizoid.telescope;

import java.util.function.Supplier;

/** Supplies the value a computed {@code name} receives. */
public final class SuperRowNameSupplier implements Supplier<String> {

  public SuperRowNameSupplier() {}

  @Override
  public String get() {
    return "made";
  }
}
