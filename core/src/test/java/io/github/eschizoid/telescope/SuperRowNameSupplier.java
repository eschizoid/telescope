package io.github.eschizoid.telescope;

import java.util.function.Supplier;

/** Supplies the value a computed {@code name} receives. */
public final class SuperRowNameSupplier implements Supplier<String> {

  /** The instance a {@code compute} row on a {@code @Bridge} field names. */
  public static final SuperRowNameSupplier INSTANCE = new SuperRowNameSupplier();

  public SuperRowNameSupplier() {}

  @Override
  public String get() {
    return "made";
  }
}
