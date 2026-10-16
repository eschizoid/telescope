package io.github.eschizoid.telescope.benchmarks;

/** Target of {@link CycleSeat}. */
public class CycleSeatDto {

  private String label;
  private CycleTeamDto next;

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public CycleTeamDto getNext() {
    return next;
  }

  public void setNext(final CycleTeamDto next) {
    this.next = next;
  }
}
