package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;

/**
 * Deep-tier outer POJO. The {@code @Bridge(McCompanyRec.class)} recurses through the {@code
 * List<McDeptBean>} which itself recurses through the {@code List<McTeamBean>} — three levels of
 * nesting + two lists, the deepest tier in the comparison suite.
 */
@Bridge(McCompanyRec.class)
public class McCompanyBean {

  private String name;
  private List<McDeptBean> departments;

  public McCompanyBean() {}

  public McCompanyBean(final String name, final List<McDeptBean> departments) {
    this.name = name;
    this.departments = departments;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<McDeptBean> getDepartments() {
    return departments;
  }

  public void setDepartments(final List<McDeptBean> departments) {
    this.departments = departments;
  }
}
