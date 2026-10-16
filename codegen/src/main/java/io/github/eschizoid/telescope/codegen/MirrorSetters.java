package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.SetterFit;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The setter of a bean property as the {@code javax.lang.model} world sees it, chosen by {@link
 * PairingRules#chooseSetter} and judged by {@link PairingRules#setterFit}, the rule the runtime
 * writers apply. Every processor that writes a bean property through its setter, and the verifier
 * that predicts the runtime mapper's refusal of one, asks here, so the three answer alike.
 */
final class MirrorSetters {

  private final Types types;
  private final Elements elements;
  private final MirrorProps props;
  private final PairingRules<TypeMirror> rules;

  MirrorSetters(final Types types, final Elements elements, final MirrorProps props) {
    this.types = types;
    this.elements = elements;
    this.props = props;
    this.rules = new PairingRules<>(props);
  }

  /**
   * Which method writes a bean property, as {@link PairingRules#chooseSetter} picks it among {@code
   * candidates}, every public instance method of one parameter named after the property's setter,
   * inherited ones included. {@code member} is null when there are none or no single one is chosen;
   * {@code writeType} is its parameter type as a member of the bean, {@code fit} how the property's
   * read type reaches it, and {@code spreads} whether the value reaches it only as the one element
   * of its variable-arity array. {@code argument} is the type a single value is passed to it as:
   * the parameter, or its component where the value spreads. {@code argumentText} is that type as a
   * generated local declares it, its erasure where it names a type variable only the method
   * declares. {@code argumentType} is {@code argumentText} where the setter has overloads, the case
   * in which a generated call assigns its value to such a local so javac binds the chosen method,
   * and null otherwise.
   */
  record SetterChoice(
    ExecutableElement member,
    TypeMirror writeType,
    SetterFit fit,
    List<ExecutableElement> candidates,
    boolean spreads,
    TypeMirror argument,
    String argumentText,
    String argumentType
  ) {}

  /**
   * The setter choice for {@code property} of {@code type}, whose read type, its getter's return
   * type as a member of {@code type}, is {@code read}, or null where it has no getter.
   */
  SetterChoice choose(final TypeElement type, final String property, final TypeMirror read) {
    final var setter = "set" + AbstractTelescopeProcessor.capitalize(property);
    final var candidates = new ArrayList<ExecutableElement>();
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(type))) {
      if (
        m.getModifiers().contains(Modifier.PUBLIC) &&
        !m.getModifiers().contains(Modifier.STATIC) &&
        m.getParameters().size() == 1 &&
        m.getSimpleName().contentEquals(setter)
      ) {
        candidates.add(m);
      }
    }
    if (candidates.isEmpty()) return new SetterChoice(null, null, null, List.of(), false, null, null, null);
    final var writeTypes = new ArrayList<TypeMirror>(candidates.size());
    final var erased = new ArrayList<TypeMirror>(candidates.size());
    final var components = new ArrayList<TypeMirror>(candidates.size());
    for (final var m : candidates) {
      final var member = (
        (ExecutableType) types.asMemberOf((DeclaredType) type.asType(), m)
      ).getParameterTypes().getFirst();
      final var erasure = types.erasure(member);
      writeTypes.add(member);
      erased.add(erasure);
      components.add(m.isVarArgs() && erasure instanceof ArrayType array ? array.getComponentType() : null);
    }
    if (read == null) {
      return candidates.size() == 1
        ? new SetterChoice(
            candidates.getFirst(),
            writeTypes.getFirst(),
            SetterFit.SAME,
            candidates,
            false,
            writeTypes.getFirst(),
            null,
            null
          )
        : new SetterChoice(null, null, null, candidates, false, null, null, null);
    }
    final var readErasure = types.erasure(read);
    final var index = rules.chooseSetter(readErasure, erased, components);
    if (index < 0) return new SetterChoice(null, null, null, candidates, false, null, null, null);
    final var spreads = rules.spreads(readErasure, erased.get(index), components.get(index));
    final var argument = spreads ? ((ArrayType) writeTypes.get(index)).getComponentType() : writeTypes.get(index);
    final var argumentText = props.mentionsTypeVariable(argument)
      ? types.erasure(argument).toString()
      : argument.toString();
    return new SetterChoice(
      candidates.get(index),
      writeTypes.get(index),
      rules.setterFit(readErasure, erased.get(index), components.get(index)),
      candidates,
      spreads,
      argument,
      argumentText,
      candidates.size() > 1 ? argumentText : null
    );
  }

  /** What a write through a property's setter asks of the value it passes. */
  enum Write {
    /** The value is converted to the setter's own type, so any single chosen setter takes it. */
    CONVERTED,
    /** Generated code passes a value of the property's type, which javac must accept uncast. */
    GENERATED,
    /**
     * A reflective writer passes a value of the property's type, checked at the call, so the setter
     * must take some value of that type.
     */
    REFLECTIVE;

    private boolean takes(final SetterFit fit) {
      return switch (this) {
        case CONVERTED -> true;
        case GENERATED -> fit.compiles();
        case REFLECTIVE -> fit.reflects();
      };
    }
  }

  /**
   * Why {@code property} of {@code type}, read as {@code read}, cannot be written through its
   * setter by {@code write}, or null: several setters bind its value equally, or the chosen setter
   * does not take what {@code write} passes it. A property with no getter or no setter is not
   * refused here. The messages are the ones the runtime writers refuse with.
   */
  String refusal(final TypeElement type, final String property, final TypeMirror read, final Write write) {
    if (read == null) return null;
    final var choice = choose(type, property, read);
    if (choice.candidates().isEmpty()) return null;
    if (choice.member() == null) {
      return PairingMessages.ambiguousSetters(
        type.getSimpleName().toString(),
        property,
        read.toString(),
        choice
          .candidates()
          .stream()
          .map(m -> m.getSimpleName() + "(" + m.getParameters().getFirst().asType() + ")")
          .sorted()
          .toList()
      );
    }
    if (write.takes(choice.fit())) return null;
    return PairingMessages.setterCannotTake(
      type.getSimpleName().toString(),
      property,
      choice.member().getSimpleName() + "(" + choice.member().getParameters().getFirst().asType() + ")",
      read.toString(),
      choice.writeType().toString()
    );
  }
}
