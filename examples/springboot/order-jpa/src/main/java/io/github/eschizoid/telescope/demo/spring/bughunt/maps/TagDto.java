package io.github.eschizoid.telescope.demo.spring.bughunt.maps;

/**
 * POJO twin of {@link Tag}. Same-name fields ({@code label} / {@code weight}) auto-infer in the
 * deep-mapping engine so the {@code Tag ↔ TagDto} element {@code Iso} requires no explicit rows.
 */
public class TagDto {

  private String label;
  private int weight;

  public TagDto() {}

  public TagDto(final String label, final int weight) {
    this.label = label;
    this.weight = weight;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public int getWeight() {
    return weight;
  }

  public void setWeight(final int weight) {
    this.weight = weight;
  }
}
