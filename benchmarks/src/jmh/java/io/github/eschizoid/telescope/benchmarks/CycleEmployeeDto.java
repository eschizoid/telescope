package io.github.eschizoid.telescope.benchmarks;

/** Target of {@link CycleEmployee}: the same two properties, the manager as a DTO. */
public class CycleEmployeeDto {

  private String name;
  private CycleEmployeeDto manager;

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public CycleEmployeeDto getManager() {
    return manager;
  }

  public void setManager(final CycleEmployeeDto manager) {
    this.manager = manager;
  }
}
