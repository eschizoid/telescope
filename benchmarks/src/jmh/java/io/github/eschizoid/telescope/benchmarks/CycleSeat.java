package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/**
 * A seat whose own bridge upper-cases its label, and whose next team can lead back to the team
 * holding it. Companion of {@link CycleSeatDto}.
 */
@Bridge(value = CycleSeatDto.class, transforms = @Transform(field = "label", using = CycleUpperFn.class))
public class CycleSeat {

  private String label;
  private CycleTeam next;

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public CycleTeam getNext() {
    return next;
  }

  public void setNext(final CycleTeam next) {
    this.next = next;
  }
}
