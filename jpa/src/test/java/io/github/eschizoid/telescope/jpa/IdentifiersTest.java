package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.MappedSuperclass;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The identifier the merge matches an entity by, found once per class from its persistence
 * annotations and read through the identifier's getter.
 */
class IdentifiersTest {

  @Test
  @DisplayName("an id annotated on the getter is read through that getter, and is generated where the getter says so")
  void anIdOnTheGetterIsRead() {
    final var identifier = Identifiers.of(OnGetter.class);
    final var element = new OnGetter();
    element.setId(7L);
    assertEquals(7L, identifier.of(element));
    assertTrue(identifier.generated());
    assertEquals("OnGetter", identifier.entity());
  }

  @Test
  @DisplayName("an embedded id is the embeddable itself, matched by its equals, and assigned")
  void anEmbeddedIdIsTheEmbeddable() {
    final var identifier = Identifiers.of(Embedded.class);
    final var element = new Embedded();
    element.setKey(new Key("a", 1));
    assertEquals(new Key("a", 1), identifier.of(element));
    assertFalse(identifier.generated());
  }

  @Test
  @DisplayName("several @Id properties are one identifier of all their values, and none while any is null")
  void severalIdPropertiesAreOneIdentifier() {
    final var identifier = Identifiers.of(Composite.class);
    final var element = new Composite();
    element.setRegion("eu");
    element.setNumber(3);
    assertEquals(List.of(3, "eu"), identifier.of(element), "the class's own properties, in name order");
    element.setRegion(null);
    assertNull(identifier.of(element));
  }

  @Test
  @DisplayName("an id a mapped superclass declares is the subclass entity's")
  void anInheritedIdIsTheEntitys() {
    final var identifier = Identifiers.of(Derived.class);
    final var element = new Derived();
    element.setId(9L);
    assertEquals(9L, identifier.of(element));
    assertTrue(identifier.generated());
  }

  @Test
  @DisplayName("a class that is no entity, an entity with no id, and one whose id has no getter have none")
  void nothingToMergeByHasNone() {
    assertSame(Identifiers.NONE, Identifiers.of(NotAnEntity.class));
    assertSame(Identifiers.NONE, Identifiers.of(NoId.class));
    assertSame(Identifiers.NONE, Identifiers.of(NoGetter.class));
  }

  @Test
  @DisplayName("a primitive id at its default is no identifier, as an unsaved row's, and any other value is one")
  void aPrimitiveIdAtItsDefaultIsNone() {
    final var identifier = Identifiers.of(Primitive.class);
    final var element = new Primitive();
    assertNull(identifier.of(element));
    element.setId(4);
    assertEquals(4L, identifier.of(element));
  }

  @Test
  @DisplayName("a boxed id holding zero is an identifier")
  void aBoxedZeroIsAnIdentifier() {
    final var element = new OnGetter();
    element.setId(0L);
    assertEquals(0L, Identifiers.of(OnGetter.class).of(element));
  }

  @Test
  @DisplayName("an id Hibernate's @UuidGenerator generates is generated, found by the mark on the annotation")
  void aHibernateGeneratorAnnotationIsGenerated() {
    assertTrue(Identifiers.of(Uuid.class).generated());
    assertFalse(Identifiers.of(Embedded.class).generated());
  }

  @Entity
  public static class Primitive {

    @Id
    @GeneratedValue
    private long id;

    public long getId() {
      return id;
    }

    public void setId(final long id) {
      this.id = id;
    }
  }

  @Entity
  public static class Uuid {

    @Id
    @UuidGenerator
    private UUID id;

    public UUID getId() {
      return id;
    }
  }

  public static class NotAnEntity {

    @Id
    private Long id;

    public Long getId() {
      return id;
    }
  }

  @Entity
  public static class NoId {

    private String name;

    public String getName() {
      return name;
    }
  }

  @Entity
  public static class NoGetter {

    @Id
    private Long id;

    public NoGetter() {
      id = 1L;
    }

    @Override
    public String toString() {
      return "NoGetter " + id;
    }
  }

  @Entity
  public static class OnGetter {

    private Long id;

    @Id
    @GeneratedValue
    public Long getId() {
      return id;
    }

    public void setId(final Long id) {
      this.id = id;
    }

    public boolean isOpen() {
      return true;
    }
  }

  @Embeddable
  public record Key(String code, int version) {}

  @Entity
  public static class Embedded {

    @EmbeddedId
    private Key key;

    public Key getKey() {
      return key;
    }

    public void setKey(final Key key) {
      this.key = key;
    }
  }

  @Entity
  @IdClass(Composite.Parts.class)
  public static class Composite {

    @Id
    private String region;

    @Id
    private Integer number;

    public String getRegion() {
      return region;
    }

    public void setRegion(final String region) {
      this.region = region;
    }

    public Integer getNumber() {
      return number;
    }

    public void setNumber(final Integer number) {
      this.number = number;
    }

    /** The identifier class an {@code @IdClass} names. */
    public static final class Parts {

      private String region;
      private Integer number;

      @Override
      public boolean equals(final Object other) {
        return (
          other instanceof Parts parts && Objects.equals(region, parts.region) && Objects.equals(number, parts.number)
        );
      }

      @Override
      public int hashCode() {
        return Objects.hash(region, number);
      }
    }
  }

  @MappedSuperclass
  public abstract static class Base {

    @Id
    @GeneratedValue
    private Long id;

    public Long getId() {
      return id;
    }

    public void setId(final Long id) {
      this.id = id;
    }
  }

  @Entity
  public static class Derived extends Base {}
}
