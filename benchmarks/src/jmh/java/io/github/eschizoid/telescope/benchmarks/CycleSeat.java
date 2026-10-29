package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * A seat whose own bridge upper-cases its label, and whose next team can lead back to the team
 * holding it. Companion of {@link CycleSeatDto}.
 */
public class CycleSeat {

  @Bridge
  public static final Mapper<CycleSeat, CycleSeatDto> DTO = mapper(
    CycleSeat.class,
    CycleSeatDto.class,
    to(CycleSeat::getLabel, CycleSeatDto::getLabel, CycleUpperFn.class)
  );

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
