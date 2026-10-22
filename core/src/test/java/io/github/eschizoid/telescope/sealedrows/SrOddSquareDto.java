package io.github.eschizoid.telescope.sealedrows;

/** A case that implements the title with a method that is no component. */
public record SrOddSquareDto(String name, String side) implements SrOddDto {
  @Override
  public String title() {
    return side;
  }
}
