package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * An employee who refers to their manager, a type that reaches itself, so its generated bridge
 * carries the path of objects being converted. Companion of {@link CycleEmployeeDto}.
 */
@Bridge(CycleEmployeeDto.class)
public class CycleEmployee {

  private String name;
  private CycleEmployee manager;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public CycleEmployee getManager() {
    return manager;
  }

  public void setManager(final CycleEmployee manager) {
    this.manager = manager;
  }
}
