package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code into} writes onto the object it was handed, whatever its runtime shape, and survives a
 * forward hook that produces nothing. Both are properties of the accessors it resolves: a writer
 * bound to a declared supertype cannot reach a setter that a subclass introduces, and a reader is
 * only safe to invoke on something that exists.
 */
class IntoTargetShapeTest {

  record Dto(String id, String label) {}

  /** Declared target: {@code label} is readable here but only writable on the subclass. */
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
    // The declared target has no setter for `label`, so the value that reaches the subclass is
    // whatever the produced instance carried. Whether the subclass setter is invoked at all is the
    // property: a writer bound to the declared type would not find it and the write would vanish.
    assertTrue(target.labelWritten(), "the subclass setter is reachable from a base-typed mapper");
    assertEquals("from-declared-target", target.getLabel(), "and it receives what the declared target carried");
  }

  @Test
  @DisplayName("a forward hook that produces nothing writes nulls rather than failing")
  void nullProductWritesNulls() {
    final var mapper = Telescope.mapper(Dto.class, Base.class).beforeForward(dto -> null);
    final var target = new Sub();
    target.setId("pre-existing");

    final var returned = mapper.into(target, new Dto("ORD-2", "mapped"));

    assertEquals(target, returned, "into returns the same reference it was given");
    assertNull(target.getId(), "a mapped property is written null rather than left alone");
  }
}
