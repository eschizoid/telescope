package io.github.eschizoid.telescope.jackson;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * One string property annotated on its field, getter and setter, or on a record component, in each
 * combination that decides whether and under which key databind reads it. Each bean's setter also
 * stores the value in {@link Probe#value}, so two beans are equal when the same value was written.
 */
public final class Combinations {

  private Combinations() {}

  /** A bean whose equality is the value its setter was last given. */
  public abstract static class Probe {

    protected String value;

    @Override
    public boolean equals(final Object other) {
      return other != null && other.getClass() == getClass() && Objects.equals(((Probe) other).value, value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "[" + value + "]";
    }
  }

  /** Renamed on the field. */
  public static final class FieldNamed extends Probe {

    @JsonProperty("f")
    private String p;

    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Renamed on the getter. */
  public static final class GetterNamed extends Probe {

    private String p;

    @JsonProperty("g")
    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Ignored on the getter, renamed on the setter: read under the setter's name. */
  public static final class GetterIgnoredSetterNamed extends Probe {

    private String p;

    @JsonIgnore
    public String getP() {
      return p;
    }

    @JsonProperty("s")
    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Renamed on the getter, ignored on the setter: nothing left to write it through. */
  public static final class GetterNamedSetterIgnored extends Probe {

    private String p;

    @JsonProperty("g")
    public String getP() {
      return p;
    }

    @JsonIgnore
    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Ignored on the field, renamed on the getter, plain setter: read under the getter's name. */
  public static final class FieldIgnoredGetterNamed extends Probe {

    @JsonIgnore
    private String p;

    @JsonProperty("g")
    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Ignored on the field, explicit on the setter. */
  public static final class FieldIgnoredSetterExplicit extends Probe {

    @JsonIgnore
    private String p;

    public String getP() {
      return p;
    }

    @JsonProperty
    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Ignored on the field alone. */
  public static final class FieldIgnored extends Probe {

    @JsonIgnore
    private String p;

    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Renamed and ignored on the field. */
  public static final class FieldNamedAndIgnored extends Probe {

    @JsonProperty("f")
    @JsonIgnore
    private String p;

    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** {@code @JsonIgnore(false)} on the field. */
  public static final class FieldNotIgnored extends Probe {

    @JsonIgnore(false)
    private String p;

    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Ignored on the getter alone. */
  public static final class GetterIgnored extends Probe {

    private String p;

    @JsonIgnore
    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Ignored on the setter alone. */
  public static final class SetterIgnored extends Probe {

    private String p;

    public String getP() {
      return p;
    }

    @JsonIgnore
    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Read-only on the setter. */
  public static final class SetterReadOnly extends Probe {

    private String p;

    public String getP() {
      return p;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Write-only on the field. */
  public static final class FieldWriteOnly extends Probe {

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String p;

    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** An alias on the field. */
  public static final class FieldAlias extends Probe {

    @JsonAlias("x2")
    private String p;

    public String getP() {
      return p;
    }

    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** Renamed. */
  public record Renamed(@JsonProperty("k") String p) {}

  /** Renamed on an accessor declared in the body. */
  public record AccessorNamed(String p) {
    @Override
    @JsonProperty("acc")
    public String p() {
      return p;
    }
  }

  /** Two aliases. */
  public record Aliased(@JsonAlias({ "x2", "x3" }) String p) {}

  /** Ignored. */
  public record Ignored(@JsonIgnore String p, String q) {}

  /** Renamed and ignored: the parameter carries {@code @JsonProperty} alone. */
  public record NamedAndIgnored(@JsonProperty("k") @JsonIgnore String p, String q) {}

  /** Read-only. */
  public record ReadOnly(@JsonProperty(access = JsonProperty.Access.READ_ONLY) String p) {}

  /** Renamed and write-only. */
  public record WriteOnly(@JsonProperty(value = "k", access = JsonProperty.Access.WRITE_ONLY) String p) {}

  /** {@code @JsonIgnore(false)}. */
  public record NotIgnored(@JsonIgnore(false) String p) {}

  /** A component named for another's name. */
  public record Swapped(@JsonProperty("p") String q, String p2) {}

  /** A setter's own settings on a bean. */
  public static final class SetterConfigured extends Probe {

    private String p;

    public String getP() {
      return p;
    }

    @JsonSetter("s")
    public void setP(final String p) {
      this.p = p;
      value = p;
    }
  }

  /** A setter's own settings on a record component. */
  public record ComponentSetter(@JsonSetter("s") String p) {}

  /** A format on a component. */
  public record Formatted(@JsonFormat(pattern = "dd.MM.yyyy") LocalDate p) {}

  /** A format on the class. */
  @JsonFormat(shape = JsonFormat.Shape.ARRAY)
  public record FormattedClass(String p) {}

  /** A component of a nested type, some of whose properties databind ignores there. */
  public record IgnoresInside(@JsonIgnoreProperties({ "n" }) Inner inner) {}

  /** Only ignores unknown keys inside the component, which the binder does already. */
  public record IgnoresUnknownInside(@JsonIgnoreProperties(ignoreUnknown = true) Inner inner) {}

  /** A nested type. */
  public record Inner(String n) {}

  /** An annotation of the application's own that bundles a rename. */
  @Retention(RetentionPolicy.RUNTIME)
  @JacksonAnnotationsInside
  @JsonProperty("cmp")
  public @interface Bundled {}

  /** A component carrying the bundle. */
  public record Composite(@Bundled String p) {}

  /** A class carrying the bundle. */
  @Bundled
  public record CompositeClass(String p) {}

  /** A color, one constant renamed. */
  public enum Color {
    @JsonProperty("r")
    RED,
    GREEN,
  }

  /** A size, one constant given an alias. */
  public enum Size {
    @JsonAlias("s")
    SMALL,
    LARGE,
  }

  /** A renamed enum as a value. */
  public record Colored(Color color) {}

  /** An aliased enum as a value. */
  public record Sized(Size size) {}

  /** A renamed enum as a map key. */
  public record ColorKeyed(Map<Color, String> byColor) {}
}
