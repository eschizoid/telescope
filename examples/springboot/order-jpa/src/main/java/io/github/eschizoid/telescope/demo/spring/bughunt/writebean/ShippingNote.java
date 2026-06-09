package io.github.eschizoid.telescope.demo.spring.bughunt.writebean;

/**
 * Immutable POJO leaf used by the {@code writeBean(Class, STRATEGY)} per-class override slice. The
 * class deliberately exposes <em>no</em> setters, <em>no</em> {@code @NoArgsConstructor}, and
 * <em>no</em> builder — the only rebuild path is the all-args constructor. This forces the
 * deep-mapping engine to honour {@code writeBean(ShippingNote.class, CONSTRUCTOR)} when a parent
 * mapping uses {@code writeBeans(SETTERS)} as the default.
 *
 * <p>Compiled with the project-wide {@code -parameters} flag so {@code CONSTRUCTOR}'s name-based
 * argument matching works (see build.gradle.kts).
 */
public final class ShippingNote {

  private final String code;
  private final String text;

  public ShippingNote(final String code, final String text) {
    this.code = code;
    this.text = text;
  }

  public String getCode() {
    return code;
  }

  public String getText() {
    return text;
  }
}
