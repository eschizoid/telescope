package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A team holding a {@link CycleSeat}, whose next team may lead back here, so the seat is a nested
 * pair with its own bridge on a cycle. Companion of {@link CycleTeamDto}.
 */
@Bridge(CycleTeamDto.class)
public class CycleTeam {

  private String name;
  private CycleSeat seat;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public CycleSeat getSeat() {
    return seat;
  }

  public void setSeat(final CycleSeat seat) {
    this.seat = seat;
  }
}
