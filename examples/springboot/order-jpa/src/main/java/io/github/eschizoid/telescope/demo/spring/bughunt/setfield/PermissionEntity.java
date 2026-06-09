package io.github.eschizoid.telescope.demo.spring.bughunt.setfield;

/**
 * Bean twin of {@link Permission}. Mutable POJO so {@code SETTERS} write strategy applies.
 *
 * <p>{@code @BeanFocus} intentionally omitted — see {@link Profile} for the rationale.
 */
public class PermissionEntity {

  private String resource;
  private String action;

  public PermissionEntity() {}

  public String getResource() {
    return resource;
  }

  public void setResource(final String resource) {
    this.resource = resource;
  }

  public String getAction() {
    return action;
  }

  public void setAction(final String action) {
    this.action = action;
  }
}
