package io.github.eschizoid.telescope.unwalked;

import java.util.function.Supplier;

/** Fills a back-reference with an empty one. */
public final class UcBackSupplier implements Supplier<UcBackDto> {

  /** The instance a {@code compute} row on a {@code @Bridge} field names. */
  public static final UcBackSupplier INSTANCE = new UcBackSupplier();

  public UcBackSupplier() {}

  @Override
  public UcBackDto get() {
    return new UcBackDto(null);
  }
}
