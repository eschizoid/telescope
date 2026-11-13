package io.github.eschizoid.telescope.jackson;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Records and beans carrying Jackson annotations, read by the binder and by databind alike. */
public final class Annotated {

  private Annotated() {}

  /** A partner whose keys differ from its component names. */
  public record Partner(
    @JsonProperty("partner_id") String id,
    @JsonAlias({ "partnerName", "name_v1" }) String name,
    @JsonIgnore String internal,
    Integer tier
  ) {}

  /** A feed holding annotated records, one under a renamed key and a list of them. */
  public record Feed(
    @JsonProperty("feed_name") String name,
    @JsonProperty("partner_info") Partner partner,
    List<Partner> partners
  ) {}

  /** A record whose key a property declared in its body renames. */
  public record Renamed(String id) {
    @Override
    @JsonProperty("ident")
    public String id() {
      return id;
    }
  }

  /** A record with a component databind only writes out and never reads. */
  public record ReadOnly(@JsonProperty(access = JsonProperty.Access.READ_ONLY) String shown, String kept) {}

  /** A record whose renamed component a document must carry. */
  public record Required(@JsonProperty(value = "partner_id", required = true) String id, String name) {}

  /** A record whose {@code @JsonIgnore(false)} ignores nothing, and whose alias is its own name. */
  public record NotIgnored(@JsonIgnore(false) String a, @JsonAlias({ "b", "x" }) String b) {}

  /** A record that only ignores unknown keys, which the binder does already. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Lenient(String id) {}

  /** Two components reading one key by {@code @JsonProperty}. */
  public record NameClash(@JsonProperty("b") String a, String b) {}

  /** A component whose alias is another component's key. */
  public record AliasClash(@JsonAlias("b") String a, String b) {}

  /** A partner bean, its annotations spread over its fields, getters and setters. */
  public static final class PartnerBean {

    @JsonProperty("partner_id")
    private String id;

    private String name;
    private String internal;
    private String secret;
    private String shown;
    private String label;

    /** Creates an empty partner. */
    public PartnerBean() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    @JsonAlias("partnerName")
    public void setName(final String name) {
      this.name = name;
    }

    @JsonIgnore
    public String getInternal() {
      return internal;
    }

    public void setInternal(final String internal) {
      this.internal = internal;
    }

    /** Written by a document and never written out: databind reads it. */
    @JsonIgnore
    public String getSecret() {
      return secret;
    }

    @JsonProperty
    public void setSecret(final String secret) {
      this.secret = secret;
    }

    /** Written out and never read: databind leaves it unset. */
    @JsonProperty
    public String getShown() {
      return shown;
    }

    @JsonIgnore
    public void setShown(final String shown) {
      this.shown = shown;
    }

    /** Renamed on its getter alone, which renames the setter with it. */
    @JsonProperty("display_label")
    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    @Override
    public boolean equals(final Object other) {
      return (
        other instanceof PartnerBean that &&
        Objects.equals(id, that.id) &&
        Objects.equals(name, that.name) &&
        Objects.equals(internal, that.internal) &&
        Objects.equals(secret, that.secret) &&
        Objects.equals(shown, that.shown) &&
        Objects.equals(label, that.label)
      );
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, name, internal, secret, shown, label);
    }

    @Override
    public String toString() {
      return "PartnerBean[" + id + ", " + name + ", " + internal + ", " + secret + ", " + shown + ", " + label + "]";
    }
  }

  /** A bean renamed on its getter and its setter apart: the setter's key is the one read. */
  public static final class SplitBean {

    private String name;

    /** Creates an empty bean. */
    public SplitBean() {}

    @JsonProperty("written_as")
    public String getName() {
      return name;
    }

    @JsonProperty("read_as")
    public void setName(final String name) {
      this.name = name;
    }
  }

  /** A bean whose required property databind does not hold a document to. */
  public static final class RequiredBean {

    private String name;

    /** Creates an empty bean. */
    public RequiredBean() {}

    @JsonProperty(required = true)
    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  /** A bean named two ways by the members it is written through. */
  public static final class TwoNames {

    @JsonProperty("f")
    private String name;

    /** Creates an empty bean. */
    public TwoNames() {}

    public String getName() {
      return name;
    }

    @JsonProperty("s")
    public void setName(final String name) {
      this.name = name;
    }
  }

  /** A record under a naming strategy. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Named(String partnerId) {}

  /** A record with an unwrapped component. */
  public record Unwrapped(@JsonUnwrapped Partner partner) {}

  /** A record built through a factory databind calls. */
  public record Created(String id) {
    @JsonCreator
    public static Created of(@JsonProperty("key") final String id) {
      return new Created(id);
    }
  }

  /** A record whose canonical constructor databind is told to call. */
  public record CreatedByConstructor(String id) {
    @JsonCreator
    public CreatedByConstructor {}
  }

  /** A bean that keeps the keys no property reads. */
  public static final class AnySetter {

    private String id;

    /** Creates an empty bean. */
    public AnySetter() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    @JsonAnySetter
    public void other(final String key, final Object value) {}
  }

  /** A bean that keeps the keys no property reads in a field. */
  public static final class AnySetterField {

    @JsonAnySetter
    private final Map<String, Object> other = new HashMap<>();

    private String id;

    /** Creates an empty bean. */
    public AnySetterField() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public Map<String, Object> other() {
      return other;
    }
  }

  /** A record with a component in a view. */
  public record Viewed(@JsonView(Annotated.class) String id) {}

  /** A record whose every property is in a view. */
  @JsonView(Annotated.class)
  public record ViewedClass(String id) {}

  /** A record that ignores a property by name. */
  @JsonIgnoreProperties({ "internal" })
  public record IgnoresByName(String id, String internal) {}

  /** A record holding a type the binder refuses, so the refusal comes from a nested type. */
  public record HoldsNamed(Named named) {}
}
