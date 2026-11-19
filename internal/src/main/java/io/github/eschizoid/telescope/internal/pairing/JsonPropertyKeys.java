package io.github.eschizoid.telescope.internal.pairing;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * How one property of a type a JSON binder builds reads, from the Jackson annotations on the
 * members it is declared by: the key, the aliases, whether it is ignored and whether a document
 * must carry it. The runtime {@code JsonBinder} reads the annotations through reflection and the
 * {@code @FromJson} processor through the compiler's model, and each describes every member as a
 * {@link Member}; the decision made from those facts is this class's alone, so both read an
 * annotated type alike.
 *
 * <ul>
 *   <li>A member is ignored when it carries {@code @JsonIgnore}, whatever else it carries. A member
 *       that carries {@code @JsonProperty} and is not ignored is explicit. A record's constructor
 *       parameter is never explicit: {@code @JsonIgnore} cannot sit on a parameter, so a component
 *       carrying both annotations hands the parameter {@code @JsonProperty} alone.
 *   <li>The property is ignored when {@code @JsonProperty(access = READ_ONLY)} is on any member;
 *       when a member is ignored and no member is explicit; and, on a bean, when a member is
 *       ignored and every setter it has is ignored, since an explicit getter or field keeps the
 *       property but leaves nothing to write it through.
 *   <li>The key is the one {@code @JsonProperty} gives on a member the property is written through,
 *       else the one it gives on the member it is read through, else the property's own name,
 *       counting only members that are not ignored. An empty {@code @JsonProperty} keeps the name.
 *   <li>The aliases are the ones {@code @JsonAlias} gives on the most preferred member carrying
 *       one, the read member last.
 *   <li>The property is required when {@code @JsonProperty(required = true)} is on any member and
 *       the type is built through a creator: databind holds a document to the key only for a
 *       property its constructor takes, which is every record component and no bean property.
 * </ul>
 */
public final class JsonPropertyKeys {

  private JsonPropertyKeys() {}

  /**
   * What {@code @JsonProperty} on one member says.
   *
   * @param value the key it gives, empty for none
   * @param readOnly whether its access is {@code READ_ONLY}
   * @param required whether it marks the key required
   */
  public record Property(String value, boolean readOnly, boolean required) {}

  /**
   * One member a property is declared by, with what its annotations say.
   *
   * @param writing whether the property is written through it, as opposed to read
   * @param setter whether it is a setter
   * @param parameter whether it is a constructor parameter
   * @param ignored whether it carries {@code @JsonIgnore} with its value true
   * @param property its {@code @JsonProperty}, or null
   * @param aliases the keys its {@code @JsonAlias} gives, or null for none
   */
  public record Member(
    boolean writing,
    boolean setter,
    boolean parameter,
    boolean ignored,
    Property property,
    List<String> aliases
  ) {}

  /**
   * How one property reads.
   *
   * @param key the key the property reads
   * @param aliases the other keys that fill it too
   * @param ignored whether the property reads no key at all and takes its default
   * @param required whether a document must carry the key
   */
  public record Keys(String key, List<String> aliases, boolean ignored, boolean required) {}

  /**
   * How the property {@code name} of {@code owner} reads, from {@code members}: the members it is
   * written through, most preferred first, then the one it is read through, if any.
   *
   * @param kind {@code component} for a record, {@code property} for a bean
   * @param creator whether the type is built through a constructor taking every property
   * @throws IllegalArgumentException when two members the property is written through name it with
   *     different keys
   */
  public static Keys decide(
    final String owner,
    final String kind,
    final String name,
    final List<Member> members,
    final boolean creator
  ) {
    final var writeNames = new LinkedHashSet<String>();
    String readName = null;
    List<String> aliases = null;
    var ignoral = false;
    var explicit = false;
    var setters = false;
    var setterKept = false;
    var readOnly = false;
    var required = false;
    for (final var member : members) {
      ignoral |= member.ignored();
      if (member.setter()) {
        setters = true;
        setterKept |= !member.ignored();
      }
      final var property = member.property();
      if (property != null) {
        readOnly |= property.readOnly();
        required |= creator && property.required();
        explicit |= !member.ignored() && !member.parameter();
        if (!member.ignored() && !property.value().isEmpty()) {
          if (member.writing()) writeNames.add(property.value());
          else readName = property.value();
        }
      }
      if (aliases == null && member.aliases() != null) aliases = List.copyOf(member.aliases());
    }
    final var ignored = readOnly || (ignoral && (!explicit || (setters && !setterKept)));
    if (!ignored && writeNames.size() > 1) {
      throw new IllegalArgumentException(PairingMessages.jsonKeyNamedTwice(kind, name, owner, List.copyOf(writeNames)));
    }
    final var key = !writeNames.isEmpty() ? writeNames.getFirst() : readName != null ? readName : name;
    return new Keys(key, aliases == null ? List.of() : aliases, ignored, required && !ignored);
  }
}
