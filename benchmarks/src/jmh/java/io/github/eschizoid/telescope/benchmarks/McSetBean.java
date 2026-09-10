package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Set;

/** Set-valued container tier, source side. Mirror of {@link McSetRec}. */
@Bridge(McSetRec.class)
public class McSetBean {

  private String name;
  private Set<McTeamBean> teams;

  public McSetBean() {}

  public McSetBean(final String name, final Set<McTeamBean> teams) {
    this.name = name;
    this.teams = teams;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public Set<McTeamBean> getTeams() {
    return teams;
  }

  public void setTeams(final Set<McTeamBean> teams) {
    this.teams = teams;
  }
}
