package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem.WellKnown;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * How a container property is written when the write does not hand the bean a container of its own:
 * by {@code into}, which refills the container an existing target holds, and by the {@link
 * BeanWriteStrategy#ADDERS} strategy, which adds each element through a method of the bean. One
 * decision for both worlds: the runtime binds what it answers to accessors, the processor to the
 * text of calls, and the verifier reports what it refuses.
 *
 * <p>The facts are the world's to answer: what a declared type is and which methods a bean has.
 * Which properties are refilled, which are filled through an adder, what the adder is called and
 * which of a bean's methods is the adder are decided here.
 *
 * @param <T> the world's type handle
 */
public final class CollectionWrites<T> {

  private final PropertySystem<T> props;

  public CollectionWrites(final PropertySystem<T> props) {
    this.props = props;
  }

  /**
   * Whether {@code into} writes a property declared as {@code declared} into the container the
   * target already holds rather than through its setter: a {@code Collection} or a {@code Map}.
   */
  public boolean refilledInPlace(final T declared) {
    final var raw = props.rawType(declared);
    return props.isSubtypeOf(raw, WellKnown.COLLECTION) || props.isSubtypeOf(raw, WellKnown.MAP);
  }

  /** How {@code into} writes one container property of the target it is given. */
  public enum IntoWrite {
    /**
     * Clear the container the target holds and give it the converted elements, through the adder
     * where one fills the property, so it keeps its identity.
     */
    REFILL,
    /** Write the converted container, or null, through the property's setter. */
    SET,
    /** Nothing can write the property: refuse the call by its name. */
    REFUSE,
  }

  /**
   * How {@code into} writes a container property, from what the target holds and what the target
   * class offers. A container the target holds that can be changed is refilled in place: with the
   * converted elements, or emptied where the converted value is null and no setter could write the
   * null. Otherwise, where the target holds no container or one that cannot be changed, the
   * converted value is written through the setter, and with no setter the call is refused.
   *
   * <p>Where an adder fills the property, a target holding no container is filled through the adder
   * too, since the adder is the class's own way to add an element.
   *
   * <p>Whether a held container can be changed is known in advance for the JDK's unmodifiable
   * containers, and otherwise learned by trying: a container whose refill throws, as one that
   * cannot change throws {@link UnsupportedOperationException} and a sorted set handed a null
   * throws {@link NullPointerException}, has its elements put back and is asked again as one that
   * cannot be changed.
   *
   * @param held whether the target's getter returns a container
   * @param changeable whether that container can be changed, as far as is known
   * @param valuePresent whether the converted value is non-null
   * @param setter whether the target class has a setter for the property
   * @param adder whether an adder fills the property
   */
  public static IntoWrite intoWrite(
    final boolean held,
    final boolean changeable,
    final boolean valuePresent,
    final boolean setter,
    final boolean adder
  ) {
    if (held && changeable) return valuePresent || adder || !setter ? IntoWrite.REFILL : IntoWrite.SET;
    if (!held && adder) return IntoWrite.REFILL;
    return setter ? IntoWrite.SET : IntoWrite.REFUSE;
  }

  /**
   * Whether {@link BeanWriteStrategy#ADDERS} fills a property declared as {@code declared} through
   * an adder: a {@code Collection}. A map has no single element to add and is written as any other
   * property is.
   */
  public boolean filledByAdder(final T declared) {
    return props.isSubtypeOf(props.rawType(declared), WellKnown.COLLECTION);
  }

  /**
   * The class an adder for a collection declared as {@code declared} has to take, erased: the
   * collection's element type, the bound of a wildcard element, or null where the declaration names
   * no element narrower than {@code Object}, which any reference parameter takes.
   */
  public T elementOf(final T declared) {
    final var arguments = props.typeArgumentsAs(declared, WellKnown.COLLECTION);
    if (arguments.size() != 1) return null;
    final var argument = arguments.getFirst();
    final var bound = props.isWildcard(argument) ? props.upperBound(argument) : argument;
    if (bound == null) return null;
    final var erased = props.erasure(bound);
    return props.typeName(erased).equals("java.lang.Object") ? null : erased;
  }

  /**
   * The adder of {@code property}, a collection of {@code element} as {@link #elementOf} gives it,
   * among the public instance methods of one parameter that {@code named} lists for each name, in
   * the order {@link #adderNames} gives the names. A method whose parameter is the element's class
   * is taken first; otherwise the one method of that name whose parameter the element is assignable
   * to. Null when no name has either, or a name has several of the second kind and none of the
   * first.
   *
   * @param named the world's public instance methods of one parameter with a given name
   * @param parameter the type a method's one parameter is declared as
   * @param <M> the world's method handle
   */
  public <M> M adderFor(
    final String property,
    final T element,
    final Function<String, List<M>> named,
    final Function<M, T> parameter
  ) {
    for (final var name : adderNames(property)) {
      M assignable = null;
      var assignableCount = 0;
      for (final var candidate : named.apply(name)) {
        final var taken = props.erasure(parameter.apply(candidate));
        if (props.isPrimitive(taken)) continue;
        if (element != null && props.sameType(taken, element)) return candidate;
        if (element == null || props.isAssignable(element, taken)) {
          assignable = candidate;
          assignableCount++;
        }
      }
      if (assignableCount == 1) return assignable;
    }
    return null;
  }

  /**
   * The names an adder of {@code property} is looked for under, in order: {@code add} followed by
   * the property's name made singular, where it ends in {@code ies}, {@code s} or {@code es}, and
   * then by the name as it is. {@code lines} gives {@code addLine}, {@code categories} gives {@code
   * addCategory} and {@code addresses} gives {@code addAddress}.
   */
  public static List<String> adderNames(final String property) {
    final var stems = new ArrayList<String>(4);
    if (property.length() > 3 && property.endsWith("ies")) {
      stems.add(property.substring(0, property.length() - 3) + "y");
    }
    if (property.length() > 1 && property.endsWith("s")) stems.add(property.substring(0, property.length() - 1));
    if (property.length() > 2 && property.endsWith("es")) stems.add(property.substring(0, property.length() - 2));
    stems.add(property);
    final var names = new ArrayList<String>(stems.size());
    for (final var stem : stems) {
      final var name = "add" + Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
      if (!names.contains(name)) names.add(name);
    }
    return List.copyOf(names);
  }
}
