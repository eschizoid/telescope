package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.Objects;

/** A partner bean, its annotations spread over its fields, getters and setters. */
@FromJson
public final class PartnerBean {

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
