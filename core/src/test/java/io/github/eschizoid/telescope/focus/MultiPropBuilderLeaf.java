package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Multi-property {@code @BeanFocus} POJO rebuilt via a static {@code builder()} (builder strategy).
 * Used as a nullable nested intermediate so the builder-chain rebuild's off-path reads ({@link
 * #note} reference, {@link #rank} primitive) are exercised against a {@code null} previous instance
 * — they must fall to their defaults rather than NPE.
 */
@BeanFocus
public class MultiPropBuilderLeaf {

  private final String label;
  private final String note;
  private final int rank;

  private MultiPropBuilderLeaf(final String label, final String note, final int rank) {
    this.label = label;
    this.note = note;
    this.rank = rank;
  }

  public String getLabel() {
    return label;
  }

  public String getNote() {
    return note;
  }

  public int getRank() {
    return rank;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String label;
    private String note;
    private int rank;

    public Builder label(final String label) {
      this.label = label;
      return this;
    }

    public Builder note(final String note) {
      this.note = note;
      return this;
    }

    public Builder rank(final int rank) {
      this.rank = rank;
      return this;
    }

    public MultiPropBuilderLeaf build() {
      return new MultiPropBuilderLeaf(label, note, rank);
    }
  }
}
