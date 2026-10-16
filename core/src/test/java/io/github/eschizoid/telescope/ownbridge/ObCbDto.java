package io.github.eschizoid.telescope.ownbridge;

public class ObCbDto {

  private String label;
  private ObCaDto a;

  public ObCbDto() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public ObCaDto getA() {
    return a;
  }

  public void setA(final ObCaDto a) {
    this.a = a;
  }
}
