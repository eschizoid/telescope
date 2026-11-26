package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.CollectionLinks;
import io.github.eschizoid.telescope.internal.pairing.CollectionWrites;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * How the elements of each collection a generated bridge writes are tied to their owner, as the
 * {@code javax.lang.model} world sees the classes: the owner's adder and remover and the element's
 * back-reference, decided by {@link CollectionLinks} for both worlds, so the bridge ties each
 * element as the runtime mapper over the same classes ties it.
 *
 * <p>The back-reference {@code @OneToMany(mappedBy)} names is read from the owner's field of the
 * collection's name or its getter, by the annotation's name, so the processor needs no persistence
 * API. Where it is the property the element would take without it, the bridge names it; where it is
 * not, the bridge takes it only where the runtime answers it, as the runtime mapper does.
 */
final class MirrorLinks {

  private static final String ONE_TO_MANY = "jakarta.persistence.OneToMany";

  private final Elements elements;
  private final Types types;
  private final MirrorProps props;
  private final CollectionWrites<TypeMirror> writes;

  MirrorLinks(final Elements elements, final Types types, final MirrorProps props) {
    this.elements = elements;
    this.types = types;
    this.props = props;
    this.writes = new CollectionWrites<>(props);
  }

  /**
   * How one collection property is tied to its owner.
   *
   * @param property the collection property
   * @param element the element class
   * @param sent the element class of the other side's collection, or null where it names none
   * @param converted whether the conversion builds each element, rather than handing the source's
   *     across; mutable beans handed across go through no adder and no remover, values do
   * @param adder the owner's adder, or null
   * @param remover the owner's remover, or null
   * @param adderSignature the adder's signature as a member of the owner, as {@code
   *     addLine(OrderLine)}, or null
   * @param removerSignature the remover's, as the adder's, or null
   * @param refusal why a write into the collection is refused: its adder is undecided and the owner
   *     has no setter for it; null otherwise
   * @param warnings what the processor warns of, one line per undecided kind it leaves unused
   * @param unused what {@code explain()} adds to the collection's line for the methods an undecided
   *     choice left unused, or null
   * @param backReference the back-reference as decided where the runtime reads {@code mappedBy}
   * @param mappedBy the property {@code @OneToMany(mappedBy)} names, where the decision took it,
   *     which the runtime takes only where its policy answers it; null otherwise
   */
  record Link(
    String property,
    TypeElement element,
    TypeElement sent,
    boolean converted,
    ExecutableElement adder,
    ExecutableElement remover,
    String adderSignature,
    String removerSignature,
    String refusal,
    List<String> warnings,
    String unused,
    CollectionLinks.BackReference backReference,
    String mappedBy
  ) {
    /** Whether a write has anything to tie beyond adding the elements. */
    boolean ties() {
      return adder != null || remover != null || backReference.property() != null;
    }

    /** The line {@code explain()} prints for this collection of {@code owner}. */
    String described(final TypeElement owner) {
      return PairingMessages.collectionWritten(
        owner.getSimpleName().toString(),
        property,
        element.getSimpleName().toString(),
        adderSignature,
        removerSignature,
        backReference.property(),
        backReference.source() == null ? null : backReference.source().name(),
        unused
      );
    }
  }

  /**
   * The link of each collection of {@code owner} a direction writes from the other side: {@code
   * written} gives, by owner property, the type of the other side's property it is converted from;
   * {@code viaRows} the owner properties a {@code via} row converts; {@code rows} the property a
   * {@code backReference} row names and {@code rowTypes} the class its getter returns, both by the
   * element class's qualified name. A row whose getter returns another class than the owner is not
   * taken.
   */
  Map<String, Link> of(
    final TypeElement owner,
    final Map<String, TypeMirror> written,
    final Set<String> viaRows,
    final Map<String, String> rows,
    final Map<String, String> rowTypes
  ) {
    final var found = new LinkedHashMap<String, Link>();
    if (owner.getKind() == ElementKind.RECORD || owner.getKind().isInterface()) return found;
    final var members = members(owner);
    for (final var entry : written.entrySet()) {
      final var name = entry.getKey();
      final var declared = propertyType(owner, name);
      if (declared == null || !writes.linked(declared)) continue;
      final var element = classOf(writes.elementOf(declared));
      if (element == null) continue;
      final var sent = entry.getValue() == null ? null : classOf(writes.elementOf(entry.getValue()));
      final var converted = viaRows.contains(name) || (sent != null && !sent.equals(element));
      final var value = CollectionLinks.isValue(
        element.getQualifiedName().toString(),
        element.getKind() == ElementKind.RECORD,
        element.getKind() == ElementKind.ENUM
      );
      final var chosen =
        converted || value
          ? members.getOrDefault(name, CollectionLinks.Members.<ExecutableElement>empty())
          : CollectionLinks.Members.<ExecutableElement>empty();
      final var key = element.getQualifiedName().toString();
      final var row = owner.getQualifiedName().contentEquals(rowTypes.getOrDefault(key, "")) ? rows.get(key) : null;
      final var mappedBy = mappedBy(owner, name);
      final Predicate<String> filled = property -> converted && fills(sent, property);
      final var decided = CollectionLinks.backReference(
        row,
        mappedBy,
        mappedBy != null && setterTakes(element, mappedBy, owner),
        converted,
        filled
      );
      found.put(
        name,
        new Link(
          name,
          element,
          sent,
          converted,
          chosen.adder(),
          chosen.remover(),
          signature(owner, chosen.adder()),
          signature(owner, chosen.remover()),
          chosen.refusal(),
          chosen.warnings(),
          chosen.unused(),
          decided,
          decided.source() == CollectionLinks.Source.MAPPED_BY ? mappedBy : null
        )
      );
    }
    return found;
  }

  /**
   * {@code method}'s name and the simple name of the class it takes as a member of {@code owner},
   * as {@code addLine(OrderLine)}, or null for no method.
   */
  private String signature(final TypeElement owner, final ExecutableElement method) {
    if (method == null) return null;
    final var member = (ExecutableType) types.asMemberOf((DeclaredType) owner.asType(), method);
    final var parameter = types.asElement(types.erasure(member.getParameterTypes().getFirst()));
    return method.getSimpleName() + "(" + parameter.getSimpleName() + ")";
  }

  /** Whether the conversion of each element writes {@code property} from {@code sent} by name. */
  boolean fills(final TypeElement sent, final String property) {
    if (sent == null) return false;
    for (final var p : props.properties(sent.asType())) if (p.name().equals(property)) return true;
    return false;
  }

  /**
   * The adder and remover of each collection of {@code owner}, as {@link CollectionLinks} picks.
   */
  private Map<String, CollectionLinks.Members<ExecutableElement>> members(final TypeElement owner) {
    final var collections = new ArrayList<CollectionLinks.Collection<TypeMirror>>();
    for (final var property : props.properties(owner.asType())) {
      if (!writes.linked(property.member())) continue;
      final var element = classOf(writes.elementOf(property.member()));
      collections.add(
        new CollectionLinks.Collection<>(
          property.name(),
          element == null ? null : element.asType(),
          hasSetter(owner, property.name())
        )
      );
    }
    if (collections.isEmpty()) return Map.of();
    final var methods = new ArrayList<CollectionLinks.Method<ExecutableElement, TypeMirror>>();
    final var ownerType = (DeclaredType) owner.asType();
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(owner))) {
      if (
        !m.getModifiers().contains(Modifier.PUBLIC) ||
        m.getModifiers().contains(Modifier.STATIC) ||
        m.getParameters().size() != 1
      ) {
        continue;
      }
      final var member = (ExecutableType) types.asMemberOf(ownerType, m);
      final var parameter = types.erasure(member.getParameterTypes().getFirst());
      if (parameter.getKind().isPrimitive() || parameter.getKind() != TypeKind.DECLARED) continue;
      methods.add(
        new CollectionLinks.Method<>(m, m.getSimpleName().toString(), parameter, returnsOf(owner, m.getReturnType()))
      );
    }
    return CollectionLinks.members(owner.getSimpleName().toString(), collections, methods, classes);
  }

  /** Whether {@code owner} has a public instance setter of one parameter for {@code property}. */
  private boolean hasSetter(final TypeElement owner, final String property) {
    final var setter = "set" + AbstractTelescopeProcessor.capitalize(property);
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(owner))) {
      if (
        m.getModifiers().contains(Modifier.PUBLIC) &&
        !m.getModifiers().contains(Modifier.STATIC) &&
        m.getParameters().size() == 1 &&
        m.getSimpleName().contentEquals(setter)
      ) {
        return true;
      }
    }
    return false;
  }

  private CollectionLinks.Returns returnsOf(final TypeElement owner, final TypeMirror returned) {
    if (returned.getKind() == TypeKind.VOID) return CollectionLinks.Returns.VOID;
    if (returned.getKind() == TypeKind.BOOLEAN) return CollectionLinks.Returns.BOOLEAN;
    if (returned.getKind() != TypeKind.DECLARED) return CollectionLinks.Returns.OTHER;
    final var erased = types.erasure(returned);
    final var returnedClass = (TypeElement) types.asElement(erased);
    if (returnedClass.getQualifiedName().contentEquals("java.lang.Object")) return CollectionLinks.Returns.OTHER;
    return types.isSubtype(types.erasure(owner.asType()), erased)
      ? CollectionLinks.Returns.OWNER
      : CollectionLinks.Returns.OTHER;
  }

  /** What the {@code javax.lang.model} world answers about classes for {@link CollectionLinks}. */
  private final CollectionLinks.Classes<TypeMirror> classes = new CollectionLinks.Classes<>() {
    @Override
    public boolean assignable(final TypeMirror from, final TypeMirror to) {
      return types.isAssignable(types.erasure(from), types.erasure(to));
    }

    @Override
    public boolean isObject(final TypeMirror type) {
      return (
        classOf(type) instanceof TypeElement element && element.getQualifiedName().contentEquals("java.lang.Object")
      );
    }

    @Override
    public String simpleName(final TypeMirror type) {
      final var element = classOf(type);
      return element == null ? type.toString() : element.getSimpleName().toString();
    }
  };

  /**
   * Whether {@code element} has a public setter for {@code property} that takes an {@code owner}.
   */
  boolean setterTakes(final TypeElement element, final String property, final TypeElement owner) {
    if (element.getKind() == ElementKind.RECORD) return false;
    final var setter = "set" + AbstractTelescopeProcessor.capitalize(property);
    final var ownerErasure = types.erasure(owner.asType());
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(element))) {
      if (
        m.getModifiers().contains(Modifier.PUBLIC) &&
        !m.getModifiers().contains(Modifier.STATIC) &&
        m.getParameters().size() == 1 &&
        m.getSimpleName().contentEquals(setter) &&
        types.isAssignable(ownerErasure, types.erasure(m.getParameters().getFirst().asType()))
      ) {
        return true;
      }
    }
    return false;
  }

  /**
   * The property {@code @OneToMany(mappedBy)} names on collection {@code property} of {@code
   * owner}: on the field of that name, the class's own first and then each superclass's, or on its
   * getter; null where neither names one.
   */
  private String mappedBy(final TypeElement owner, final String property) {
    for (var type = owner; type != null; type = superclassOf(type)) {
      for (final var field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
        if (!field.getSimpleName().contentEquals(property) || field.getModifiers().contains(Modifier.STATIC)) continue;
        return mappedByOn(field);
      }
    }
    final var getterNames = List.of(
      "get" + AbstractTelescopeProcessor.capitalize(property),
      "is" + AbstractTelescopeProcessor.capitalize(property)
    );
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(owner))) {
      if (m.getParameters().isEmpty() && getterNames.contains(m.getSimpleName().toString())) {
        final var named = mappedByOn(m);
        if (named != null) return named;
      }
    }
    return null;
  }

  private String mappedByOn(final Element annotated) {
    for (final AnnotationMirror annotation : annotated.getAnnotationMirrors()) {
      final var type = (TypeElement) annotation.getAnnotationType().asElement();
      if (!type.getQualifiedName().contentEquals(ONE_TO_MANY)) continue;
      for (final var value : annotation.getElementValues().entrySet()) {
        if (!value.getKey().getSimpleName().contentEquals("mappedBy")) continue;
        final var named = value.getValue().getValue().toString();
        return named.isEmpty() ? null : named;
      }
    }
    return null;
  }

  private TypeElement superclassOf(final TypeElement type) {
    final var parent = type.getSuperclass();
    if (parent.getKind() != TypeKind.DECLARED) return null;
    final var element = (TypeElement) types.asElement(parent);
    return element.getQualifiedName().contentEquals("java.lang.Object") ? null : element;
  }

  private TypeMirror propertyType(final TypeElement owner, final String name) {
    for (final var property : props.properties(owner.asType()))
      if (property.name().equals(name)) return property.member();
    return null;
  }

  private TypeElement classOf(final TypeMirror type) {
    if (type == null) return null;
    final var erased = types.erasure(type);
    return erased.getKind() == TypeKind.DECLARED ? (TypeElement) types.asElement(erased) : null;
  }
}
