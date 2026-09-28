package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  @DisplayName("a slot declared as a concrete container is left null rather than given a List it cannot hold")
  void aConcreteContainerSlotIsNotHandedTheInterfaceSingleton() {
    // The substitution table answers for a family, so what it returns for a List is an immutable
    // singleton an ArrayList-declared slot cannot hold. Telling the two apart needs the class
    // behind the declared type, and a bean declares this property as a parameterized type rather
    // than a class.
    final var bean = Telescope.fromMap(
      ConcreteContainerBean.class,
      extract("tags", ConcreteContainerBean::getTags, v -> v)
    ).forward(Map.<String, Object>of());

    assertNull(bean.getTags(), "a slot with no value it can hold is left null");
  }

  /** A bean with setters for two properties and one property computed from them. */
  public static class DerivedBean {

    private String first;
    private String last;

    public String getFirst() {
      return first;
    }

    public void setFirst(final String first) {
      this.first = first;
    }

    public String getLast() {
      return last;
    }

    public void setLast(final String last) {
      this.last = last;
    }

    public String getFull() {
      return first + " " + last;
    }
  }

  @Test
  @DisplayName("a required row on a writable bean property refuses an absent key and converts a present one")
  void aRequiredRowOnABeanProperty() {
    // The key differs from the property, so the refusal has to name both for either to be found.
    final var mapper = Telescope.fromMap(
      DerivedBean.class,
      required("given_name", DerivedBean::getFirst, Object::toString)
    );

    final var refusal = assertThrows(IllegalArgumentException.class, () -> mapper.forward(Map.of()));
    assertEquals(
      "Telescope.fromMap: the map carries no value for required key \"given_name\" (property 'first') of DerivedBean",
      refusal.getMessage()
    );
    assertEquals("Ada", mapper.forward(Map.of("given_name", "Ada")).getFirst());
  }

  @Test
  @DisplayName("a required row on a property the bean cannot write is refused while the mapper is built")
  void aRequiredRowOnADerivedPropertyIsRefused() {
    // The bean has nowhere to put the value, so a source that carries it would come back without
    // it. Refusing the row is the only answer that does not drop data the caller asked to bind.
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.fromMap(DerivedBean.class, required("full_name", DerivedBean::getFull, Object::toString))
    );
    assertEquals(
      "Telescope.fromMap: a row names property 'full' of DerivedBean, which has no setter, builder method or" +
        " constructor parameter to write it, so the value read for key \"full_name\" would be dropped. Remove" +
        " the row, or give DerivedBean a way to write 'full'.",
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName("an extract row on a property the bean cannot write is refused the same way")
  void anExtractRowOnADerivedPropertyIsRefused() {
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.fromMap(DerivedBean.class, extract("full_name", DerivedBean::getFull, Object::toString))
    );
    assertTrue(refusal.getMessage().contains("property 'full' of DerivedBean"), refusal::getMessage);
  }

  @Test
  @DisplayName("a derived property no row names is left alone, since nothing was asked of it")
  void anUnnamedDerivedPropertyIsAccepted() {
    final var bean = Telescope.fromMap(
      DerivedBean.class,
      extract("given_name", DerivedBean::getFirst, Object::toString)
    ).forward(Map.of("given_name", "Ada"));

    assertEquals("Ada null", bean.getFull());
  }

  /**
   * A bean written through a static builder, whose builder has no method for the derived property.
   */
  public static final class BuiltBean {

    private final String code;

    private BuiltBean(final String code) {
      this.code = code;
    }

    public String getCode() {
      return code;
    }

    public String getLabel() {
      return "#" + code;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String code;

      public Builder code(final String code) {
        this.code = code;
        return this;
      }

      public BuiltBean build() {
        return new BuiltBean(code);
      }
    }
  }

  @Test
  @DisplayName("on a builder-written bean, a row is refused for a property the builder has no method for")
  void aBuilderWrittenBeanRefusesARowItCannotWrite() {
    assertEquals(
      "7",
      Telescope.fromMap(BuiltBean.class, extract("c", BuiltBean::getCode, Object::toString))
        .forward(Map.of("c", 7))
        .getCode()
    );
    assertThrows(IllegalArgumentException.class, () ->
      Telescope.fromMap(BuiltBean.class, extract("l", BuiltBean::getLabel, Object::toString))
    );
  }
}
