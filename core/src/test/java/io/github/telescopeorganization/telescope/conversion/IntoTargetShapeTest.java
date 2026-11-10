package io.github.telescopeorganization.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.telescopeorganization.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code into} writes onto the object it was handed, whatever its runtime shape, and survives a
 * forward hook that produces nothing. Both are properties of the accessors it resolves: a writer
 * bound to a declared supertype cannot reach a setter overload that a subclass introduces, and a
 * reader is only safe to invoke on something that exists.
 */
class IntoTargetShapeTest {

  record Dto(String id, String label) {}

  /**
   * Declared target: {@code label} is written here through a setter taking any {@link
   * CharSequence}, and the subclass adds the overload taking the property's own type.
   */
  public static class Base {

    private String id;
    private String label = "from-declared-target";

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(final CharSequence label) {
      this.label = label == null ? null : label.toString();
    }
  }

  /** The concrete shape a caller actually passes — an entity hierarchy in miniature. */
  public static class Sub extends Base {

    private String subLabel = "untouched";
    private boolean labelWritten;

    @Override
    public String getLabel() {
      return subLabel;
    }

    public void setLabel(final String label) {
      this.labelWritten = true;
      this.subLabel = label;
    }

    boolean labelWritten() {
      return labelWritten;
    }
  }

  @Test
  @DisplayName("a setter introduced by the runtime subclass is used, not skipped")
  void writesReachTheRuntimeClassSetters() {
    final var mapper = Telescope.mapper(Dto.class, Base.class);
    final var target = new Sub();

    mapper.into(target, new Dto("ORD-1", "mapped"));

    assertEquals("ORD-1", target.getId(), "the base setter still runs");
    // On the subclass a String binds its own overload, which the declared target does not have: a
    // writer bound to the declared type calls the CharSequence setter and the subclass never sees
    // the write.
    assertTrue(target.labelWritten(), "the subclass setter is reachable from a base-typed mapper");
    assertEquals("mapped", target.getLabel(), "and it receives the mapped value");
  }

  @Test
  @DisplayName("a forward hook that produces nothing writes nulls rather than failing")
  void nullProductWritesNulls() {
    final var mapper = Telescope.mapper(Dto.class, Base.class).beforeForward(dto -> null);
    final var target = new Sub();
    target.setId("pre-existing");

    final var returned = mapper.into(target, new Dto("ORD-2", "mapped"));

    assertSame(target, returned, "into returns the same reference it was given");
    assertNull(target.getId(), "a mapped property is written null rather than left alone");
    // The null path resolves its own writers, so it needs the same subclass reach the mapped path
    // has: asserting only a base-declared property would pass with writers bound to the supertype.
    assertTrue(target.labelWritten(), "the subclass setter is reached on the null path too");
    assertNull(target.getLabel());
  }
}
