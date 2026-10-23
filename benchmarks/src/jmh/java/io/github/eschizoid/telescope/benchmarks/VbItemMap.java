package io.github.eschizoid.telescope.benchmarks;

/** The hand-written element converter {@link ViaRowBenchmark}'s rows call. */
public final class VbItemMap {

  private VbItemMap() {}

  public static VbItemDto forward(final VbItem item) {
    return new VbItemDto(item.name(), item.n());
  }

  public static VbItem backward(final VbItemDto dto) {
    return new VbItem(dto.label(), dto.n());
  }
}
