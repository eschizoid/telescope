package io.github.eschizoid.telescope.nativesmoke;

/**
 * The {@code @Bridge} target and bean-mapper target for {@link SmokeBeanA} — same property names /
 * types (a bijection), so both the runtime {@code Telescope.mapper(...)} deep-map and the codegen
 * {@code @Bridge} bijection resolve without extra mapping rows.
 */
public final class SmokeBeanB {

  private String id;
  private String email;
  private String name;

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getName() {
    return name;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
