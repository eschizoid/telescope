package io.github.eschizoid.telescope.benchmarks;

/**
 * Deep-tier leaf POJO. Two-scalar field bean — no further nesting. Used as a list element inside
 * {@link McDeptBean} which is itself a list element inside {@link McCompanyBean}.
 */
public class McTeamBean {

  private String name;
  private int headcount;

  public McTeamBean() {}

  public McTeamBean(final String name, final int headcount) {
    this.name = name;
    this.headcount = headcount;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public int getHeadcount() {
    return headcount;
  }

  public void setHeadcount(final int headcount) {
    this.headcount = headcount;
  }
}
