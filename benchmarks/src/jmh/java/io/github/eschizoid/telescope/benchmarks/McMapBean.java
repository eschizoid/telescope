package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Map;

/** Map-valued container tier, source side. Mirror of {@link McMapRec}. */
@Bridge(McMapRec.class)
public class McMapBean {

  private String name;
  private Map<String, McTeamBean> teams;

  public McMapBean() {}

  public McMapBean(final String name, final Map<String, McTeamBean> teams) {
    this.name = name;
    this.teams = teams;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public Map<String, McTeamBean> getTeams() {
    return teams;
  }

  public void setTeams(final Map<String, McTeamBean> teams) {
    this.teams = teams;
  }
}
