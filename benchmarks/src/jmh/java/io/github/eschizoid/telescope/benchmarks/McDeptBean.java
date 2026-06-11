package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/**
 * Deep-tier middle POJO. Holds a {@code name} scalar plus a {@code List<McTeamBean>} — exercises
 * the {@code List<X> ↔ List<Y>} lift that both MapStruct (auto-iterates when an element-mapping
 * method exists) and telescope (auto-lifts via {@code Iso.liftList}) handle natively.
 */
public class McDeptBean {

  private String name;
  private List<McTeamBean> teams;

  public McDeptBean() {}

  public McDeptBean(final String name, final List<McTeamBean> teams) {
    this.name = name;
    this.teams = teams;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<McTeamBean> getTeams() {
    return teams;
  }

  public void setTeams(final List<McTeamBean> teams) {
    this.teams = teams;
  }
}
