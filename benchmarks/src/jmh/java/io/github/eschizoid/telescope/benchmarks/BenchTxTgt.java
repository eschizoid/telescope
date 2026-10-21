package io.github.eschizoid.telescope.benchmarks;

/** The target of {@link BenchTxSrc}, with one {@code String} value. */
public class BenchTxTgt {

  private String value;

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
