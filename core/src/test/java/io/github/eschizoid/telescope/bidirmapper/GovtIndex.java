package io.github.eschizoid.telescope.bidirmapper;

/** Source POJO: sorId is a String (mismatched with the target's Integer). */
public class GovtIndex {

  private String sorId;

  public GovtIndex() {}

  public GovtIndex(final String sorId) {
    this.sorId = sorId;
  }

  public String getSorId() {
    return sorId;
  }

  public void setSorId(final String sorId) {
    this.sorId = sorId;
  }
}
