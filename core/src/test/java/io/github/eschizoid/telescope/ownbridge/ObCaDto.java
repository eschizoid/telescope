package io.github.eschizoid.telescope.ownbridge;

public class ObCaDto {

  private String name;
  private ObCbDto b;

  public ObCaDto() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public ObCbDto getB() {
    return b;
  }

  public void setB(final ObCbDto b) {
    this.b = b;
  }
}
