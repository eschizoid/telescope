package io.github.telescopeorganization.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which bean properties no member of the class writes: the derived getters a strict mapper leaves
 * unpaired rather than refusing.
 */
class NoWriteMemberTest {

  public static class Named {

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

  public static final class Built {

    private final String value;

    private Built(final String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public String getShout() {
      return value.toUpperCase(Locale.ROOT);
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String value;

      public Builder withValue(final String value) {
        this.value = value;
        return this;
      }

      public Built build() {
        return new Built(value);
      }
    }
  }

  public static final class Constructed {

    private final String value;
    private final int size;

    public Constructed(final String value, final int size) {
      this.value = value;
      this.size = size;
    }

    public String getValue() {
      return value;
    }

    public int getSize() {
      return size;
    }

    public boolean isEmpty() {
      return size == 0;
    }
  }

  public record Point(int x, int y) {}

  @Test
  @DisplayName("a getter with no setter, builder member or constructor parameter of its name is written by nothing")
  void aDerivedGetterHasNoWriteMember() {
    assertTrue(Beans.noWriteMember(Named.class, "full"));
    assertTrue(Beans.noWriteMember(Built.class, "shout"));
    assertTrue(Beans.noWriteMember(Constructed.class, "empty"));
  }

  @Test
  @DisplayName("a setter, a builder member under any of its names, or a constructor parameter writes a property")
  void eachKindOfMemberWritesAProperty() {
    assertFalse(Beans.noWriteMember(Named.class, "first"));
    assertFalse(Beans.noWriteMember(Built.class, "value"));
    assertFalse(Beans.noWriteMember(Constructed.class, "value"));
    assertFalse(Beans.noWriteMember(Constructed.class, "size"));
  }

  @Test
  @DisplayName("a record component is written by the canonical constructor")
  void aRecordComponentIsWritten() {
    assertFalse(Beans.noWriteMember(Point.class, "x"));
  }
}
