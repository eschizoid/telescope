package io.github.eschizoid.telescope.benchmarks;

/** Target of {@link CycleTeam}. */
public class CycleTeamDto {

  private String name;
  private CycleSeatDto seat;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public CycleSeatDto getSeat() {
    return seat;
  }

  public void setSeat(final CycleSeatDto seat) {
    this.seat = seat;
  }
}
