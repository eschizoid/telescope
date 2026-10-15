package io.github.eschizoid.telescope.sealedroots;

import java.util.Objects;

/** A bean case, so a mapper can write into an existing one. */
public final class SrSquareDto implements SrShapeDto {

  private double side;
  private String label;

  public SrSquareDto() {}

  public SrSquareDto(final double side, final String label) {
    this.side = side;
    this.label = label;
  }

  public double getSide() {
    return side;
  }

  public void setSide(final double side) {
    this.side = side;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof SrSquareDto that && side == that.side && Objects.equals(label, that.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(side, label);
  }

  @Override
  public String toString() {
    return "SrSquareDto[side=" + side + ", label=" + label + "]";
  }
}
