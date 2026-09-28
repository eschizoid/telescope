package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code fromMap} leaves in a bean's slots, and what it refuses to leave.
 *
 * <p>A bean reaches its properties through a getter and is filled through whatever writer its shape
 * offers, and the two do not name the same set: a property computed from other state is readable
 * and not writable. So a decision taken while reading the properties is not necessarily one the
 * write ever consults.
 */
class FromMapBeanSlotsTest {

  /** A property whose declared type is a concrete implementation rather than the interface. */
  public static class ConcreteContainerBean {

    private ArrayList<String> tags;

    public ArrayList<String> getTags() {
      return tags;
    }

    public void setTags(final ArrayList<String> tags) {
      this.tags = tags;
    }
  }

  @Test
  @DisplayName("a slot declared as a concrete container is left empty rather than given one it cannot hold")
  void aConcreteContainerSlotIsNotHandedTheInterfaceSingleton() {
    // The substitution table answers for a family, so what it returns for a List is an immutable
    // singleton an ArrayList-declared slot cannot hold. Reading the declared type as a Class is
    // what
    // tells the two apart, and a generic declaration is not one.
    final var bean = Telescope.fromMap(
      ConcreteContainerBean.class,
      extract("tags", ConcreteContainerBean::getTags, v -> v)
    ).forward(Map.<String, Object>of());

    assertNull(bean.getTags(), "a slot with no value it can hold is left empty");
  }
}
