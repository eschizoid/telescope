package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem.WellKnown;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

/**
 * The shared pairing decision rules — one implementation, two consumers. The runtime mapper
 * construction and the compile-time verifier both delegate every pairing decision here: which
 * conversion a (source, target) field-type pair takes ({@link #decidePair}), which fields of a type
 * pair match by name ({@link #matchFields}), and which container view a parameterized type presents
 * ({@link #containerViewOf}). Only the type-system <em>primitives</em> differ per world — supplied
 * through {@link PropertySystem} — so the rules cannot drift between compile time and construction
 * time.
 *
 * <p>Decision order in {@link #decidePair} is load-bearing — it IS the runtime lattice: same type
 * (identity, or a copy for a container) → primitive/wrapper → enum by constant name → same-kind
 * subtype copy → reflectable recursion → sealed-case dispatch → cross-{@code Optional} bridge →
 * same-kind container lift → incompatible. Subtype copy must precede reflectable recursion: a
 * container subclass that declares no type parameters of its own ({@code class ImageUrls extends
 * ArrayList<ImageUrl>}) counts as reflectable, and bean-decomposing it would fail at the JDK
 * boundary (private lookup into {@code java.base} is rejected) — the copy branch intercepts those
 * pairs first, and a pair of containers it does not take never reaches recursion: it goes to the
 * container lift, which converts each element or refuses the pair by name.
 *
 * @param <T> the world's type handle
 */
public final class PairingRules<T> {

  private final PropertySystem<T> props;

  public PairingRules(final PropertySystem<T> props) {
    this.props = props;
  }

  /**
   * Decide the conversion for one (source type, target type) field pair, for a mapper that converts
   * in both directions. Never returns null.
   */
  public PairDecision<T> decidePair(final T srcType, final T tgtType, final String componentName) {
    return decidePair(srcType, tgtType, componentName, false);
  }

  /**
   * Decide the conversion for one (source type, target type) field pair. {@code forwardOnly} is
   * true for a mapper with no backward direction to satisfy: {@code mapperForward}, and a lenient
   * {@code @Bridge}, whose backward is documented as lossy. It changes only which enum pairs
   * convert, since an enum pair converts in the directions the mapper runs. Never returns null.
   */
  public PairDecision<T> decidePair(
    final T srcType,
    final T tgtType,
    final String componentName,
    final boolean forwardOnly
  ) {
    // (a) Same type → identity, or a copy for a container the allocation table can rebuild.
    if (props.sameType(srcType, tgtType)) return unchanged(srcType);

    // (a.0) Two sealed interfaces given type arguments → dispatch on the case, where the arguments
    // allow it. One with none on either side is a class type and takes (b.1) below.
    if (sealedWithArgumentsPair(srcType, tgtType)) return sealedWithArguments(srcType, tgtType, componentName);

    // (a.0b) Two records or beans given type arguments → recurse into the pair, where both give
    // the same argument for every type variable a member uses. One with none on either side is a
    // class type and takes (b) below.
    if (genericClassPair(srcType, tgtType)) {
      return argumentsInUseAgree(srcType, tgtType)
        ? new PairDecision.RecursePair<>()
        : new PairDecision.Incompatible<>(
            PairingMessages.genericArgumentsDiffer(componentName, props.typeName(srcType), props.typeName(tgtType))
          );
    }

    if (props.isClassType(srcType) && props.isClassType(tgtType)) {
      // (a.1) Primitive ↔ wrapper over the same scalar — null-safe box/unbox.
      if (primitiveWrapperPair(srcType, tgtType)) return new PairDecision.PrimitiveWrapper<>();

      // (a.1b) Two different enums convert constant to constant by name, which needs a same-named
      // constant on the receiving side of each direction the mapper runs. Any other enum pair is
      // refused naming the constants that have no counterpart.
      if (bothEnums(srcType, tgtType)) {
        final var correspondence = enumCorrespondence(srcType, tgtType);
        if (correspondence.converts(forwardOnly)) return new PairDecision.EnumByName<>();
        return new PairDecision.Incompatible<>(
          PairingMessages.unmatchedEnumConstants(
            componentName,
            props.typeName(srcType),
            props.typeName(tgtType),
            correspondence.missingOnTarget(),
            forwardOnly ? List.of() : correspondence.missingOnSource()
          )
        );
      }

      // (a.2) Same-kind Collection / Map pair written without type arguments on both sides:
      // element copy, which converts nothing. It is right only where the elements can be shown to
      // need no conversion. Both sides fix the same element types, and the same key type for a
      // map; or both are generic classes used raw, which name no element type for either side to
      // differ by; or one is used raw and the other fixes every argument to Object, which holds
      // any element. Two classes that declare no type parameters of their own and fix different
      // element types, such as `Leaves extends ArrayList<Leaf>` against `LeafDtos extends
      // ArrayList<LeafDto>`, skip both this and (b) for the container lift in (c), which converts
      // each element. A side used raw against a side fixing anything narrower than Object is
      // refused: its elements are of no type anything has said, so neither a copy nor a
      // conversion can be planned for them.
      //
      // The copy is also gated on kind-discriminator agreement and on both sides being buildable.
      // An interface or abstract class is buildable exactly when the allocation table names a
      // default implementation for it, which both worlds decide the same way and the runtime copy
      // then allocates. A concrete class is probed by the runtime; the compile-time world cannot
      // probe it and answers UNKNOWN, which is accepted here, so the generated code's own checks
      // on the class it allocates are what refuse one that has no reachable constructor. A copy
      // provably not buildable falls through to the remaining branches.
      final var collection = sameKindCollection(srcType, tgtType);
      final var map = sameKindMap(srcType, tgtType);
      final var elements = collection
        ? elementsMatch(srcType, tgtType, WellKnown.COLLECTION)
        : map
          ? elementsMatch(srcType, tgtType, WellKnown.MAP)
          : ElementMatch.SAME;
      if (elements == ElementMatch.UNPROVABLE) {
        final var srcRaw = !namesTypes(props.typeArgumentsAs(srcType, map ? WellKnown.MAP : WellKnown.COLLECTION));
        return new PairDecision.Incompatible<>(
          PairingMessages.unprovableRawElements(
            componentName,
            props.typeName(srcRaw ? srcType : tgtType),
            props.typeName(srcRaw ? tgtType : srcType)
          )
        );
      }
      if (elements == ElementMatch.SAME) {
        final var classless = (collection || map) ? copyRefusal(srcType, tgtType) : null;
        if (classless != null) return new PairDecision.Incompatible<>(classless);
        final var allocable =
          (collection || map) && props.copyAllocability(srcType, tgtType) != PropertySystem.Allocability.NOT_ALLOCABLE;
        if (collection && allocable) return new PairDecision.CollectionCopy<>();
        if (map && allocable) {
          final var keys = props.typeArgumentsAs(tgtType, WellKnown.MAP);
          if (!keys.isEmpty() && unorderableSortedKeys(keys.getFirst(), tgtType)) {
            return new PairDecision.Incompatible<>(
              PairingMessages.unorderableSortedKey(
                componentName,
                props.typeName(tgtType),
                props.typeName(keys.getFirst())
              )
            );
          }
          return new PairDecision.MapCopy<>();
        }

        // (b) Both reflectable (record or bean) → recurse into the nested pair. Two containers are
        // never recursed into: a container's elements are no property of it, so decomposing one
        // as a bean rebuilds the target with none of them. A pair of containers the copy above
        // does not take, because the kinds differ or one side cannot be allocated, goes on to the
        // container views, which convert each element or refuse the pair by name.
        if (!bothContainers(srcType, tgtType) && reflectable(srcType) && reflectable(tgtType)) {
          return new PairDecision.RecursePair<>();
        }

        // (b.1) Two sealed interfaces → dispatch on the case. Neither has properties of its own to
        // walk, and its permitted subclasses are every value it can hold, so a value converts
        // through the pair its case belongs to.
        if (props.isSealedInterfaceType(srcType) && props.isSealedInterfaceType(tgtType)) {
          return new PairDecision.DispatchCases<>();
        }
      }
    }

    // (c) Container views. Cross-Optional bridge first, then same-kind lift.
    final var srcView = containerViewOf(srcType);
    final var tgtView = containerViewOf(tgtType);

    // (c.1) Optional<X> ↔ nullable scalar/record/bean, either direction.
    if (srcView != null && srcView.kind() == ContainerView.Kind.OPTIONAL && tgtView == null) {
      return new PairDecision.OptionalToNullable<>(srcView.elementType(), tgtType);
    }
    if (tgtView != null && tgtView.kind() == ContainerView.Kind.OPTIONAL && srcView == null) {
      return new PairDecision.NullableToOptional<>(srcType, tgtView.elementType());
    }

    // A Collection-declared side takes the other's shape before anything else looks at the pair,
    // so the lift and the allocator are chosen by what is actually being built. Two of them settle
    // on a list, which is what a Collection guarantees on its own: iteration, and nothing about
    // duplicates. List against Set stays a mismatch -- those are different shapes, not one shape
    // named loosely.
    final var src = settledAgainst(srcView, tgtView);
    final var tgt = settledAgainst(tgtView, srcView);

    if (src != null && tgt != null && src.kind() == tgt.kind()) {
      // Map<K, X> ↔ Map<K, Y>: keys must match exactly; lifting preserves the source keys.
      if (src.kind() == ContainerView.Kind.MAP_VALUES && !props.sameType(src.keyType(), tgt.keyType())) {
        return new PairDecision.Incompatible<>(
          PairingMessages.incompatibleMapKeys(
            componentName,
            props.typeName(src.keyType()),
            props.typeName(tgt.keyType())
          )
        );
      }
      if (
        tgt.kind() == ContainerView.Kind.MAP_VALUES &&
        !(decidePair(src.elementType(), tgt.elementType(), componentName, forwardOnly) instanceof
            PairDecision.Incompatible) &&
        unorderableSortedKeys(tgt.keyType(), tgtType)
      ) {
        return new PairDecision.Incompatible<>(
          PairingMessages.unorderableSortedKey(componentName, props.typeName(tgtType), props.typeName(tgt.keyType()))
        );
      }
      if (
        tgt.kind() == ContainerView.Kind.SET &&
        !(decidePair(src.elementType(), tgt.elementType(), componentName, forwardOnly) instanceof
            PairDecision.Incompatible) &&
        unorderableSortedTarget(src.elementType(), tgt.elementType(), tgtType)
      ) {
        return new PairDecision.Incompatible<>(
          PairingMessages.unorderableSortedElement(
            componentName,
            props.typeName(tgtType),
            props.typeName(tgt.elementType())
          )
        );
      }
      return new PairDecision.LiftContainer<>(src, tgt);
    }

    return new PairDecision.Incompatible<>(
      PairingMessages.incompatibleShapes(componentName, props.typeName(srcType), props.typeName(tgtType))
    );
  }

  /** Whether both sides are sealed interfaces and at least one of them is given type arguments. */
  private boolean sealedWithArgumentsPair(final T srcType, final T tgtType) {
    if (!props.isSealedInterfaceType(srcType) || !props.isSealedInterfaceType(tgtType)) return false;
    return !props.isClassType(srcType) || !props.isClassType(tgtType);
  }

  /**
   * Whether both sides are records or beans, neither of them a container, and at least one of them
   * is given type arguments.
   *
   * <p>Such a pair converts as its two classes pair with each other, which reads the target's type
   * variables as the source's in the same position ({@link #alignedToSource}) and is the same
   * conversion whatever arguments the component gives them. That is the conversion the component's
   * arguments call for where both sides give the same argument for each variable a member passes
   * across ({@link #argumentsInUseAgree}).
   */
  private boolean genericClassPair(final T srcType, final T tgtType) {
    if (props.isClassType(srcType) && props.isClassType(tgtType)) return false;
    // A wildcard or type variable names no class of its own to pair.
    if (!namesClass(srcType) || !namesClass(tgtType)) return false;
    if (containerViewOf(srcType) != null || containerViewOf(tgtType) != null) return false;
    return reflectable(props.rawType(srcType)) && reflectable(props.rawType(tgtType));
  }

  /** Whether {@code type} is a class, or a class given type arguments. */
  private boolean namesClass(final T type) {
    return props.isClassType(type) || !props.typeArguments(type).isEmpty();
  }

  /**
   * Whether two generic classes give the same argument for every type variable a member of either
   * uses, position by position, which is the pairing {@link #alignedToSource} reads them in. A
   * variable no member uses carries no value across, so its argument is not compared, and a pair
   * whose members use none converts whatever arguments it is given, a side used raw included. A
   * used variable with no counterpart in the same position on the other side, an argument a raw
   * side does not give, and a wildcard, which names no one type, all refuse the pair.
   */
  private boolean argumentsInUseAgree(final T srcType, final T tgtType) {
    final var srcVariables = props.typeParameters(srcType);
    final var tgtVariables = props.typeParameters(tgtType);
    final var srcInUse = props.typeParametersInUse(srcType);
    final var tgtInUse = props.typeParametersInUse(tgtType);
    final var srcArguments = props.typeArguments(srcType);
    final var tgtArguments = props.typeArguments(tgtType);
    for (int i = 0; i < Math.max(srcVariables.size(), tgtVariables.size()); i++) {
      final var used =
        (i < srcVariables.size() && srcInUse.contains(srcVariables.get(i))) ||
        (i < tgtVariables.size() && tgtInUse.contains(tgtVariables.get(i)));
      if (!used) continue;
      if (i >= srcArguments.size() || i >= tgtArguments.size()) return false;
      if (props.isWildcard(srcArguments.get(i)) || props.isWildcard(tgtArguments.get(i))) return false;
      if (!props.sameType(srcArguments.get(i), tgtArguments.get(i))) return false;
    }
    return true;
  }

  /**
   * Whether two sides give the same type arguments, one by one, none of them a wildcard, which
   * names no one type.
   */
  private boolean sameArguments(final T srcType, final T tgtType) {
    final var srcArguments = props.typeArguments(srcType);
    final var tgtArguments = props.typeArguments(tgtType);
    if (srcArguments.size() != tgtArguments.size()) return false;
    for (int i = 0; i < srcArguments.size(); i++) {
      if (props.isWildcard(srcArguments.get(i)) || props.isWildcard(tgtArguments.get(i))) return false;
      if (!props.sameType(srcArguments.get(i), tgtArguments.get(i))) return false;
    }
    return true;
  }

  /**
   * The decision for two sealed interfaces at least one of which is given type arguments.
   *
   * <p>A case converts as its class pairs with the other side's case, the same conversion whatever
   * arguments the component gives the interfaces, and a type variable a case passes across is read
   * as the paired case's variable in the same position ({@link #alignedToSource}). That conversion
   * is the one the component's arguments call for only when both sides give the same arguments, one
   * by one, so any other pair is refused, as is a wildcard argument, which names no one type.
   *
   * <p>Each case of either interface, and each case of a sealed case below it, then has to be able
   * to hold the component's arguments. A case giving the interface its own type variable can; one
   * giving it a type with no variable in it can when that type is the component's argument, and is
   * refused otherwise, since no value of the component's type is of that case. A case giving it a
   * type built over its own variable, such as {@code List<T>}, is refused too, because telling
   * whether it fits would mean binding the variable inside that type.
   */
  private PairDecision<T> sealedWithArguments(final T srcType, final T tgtType, final String componentName) {
    if (!sameArguments(srcType, tgtType)) {
      return new PairDecision.Incompatible<>(
        PairingMessages.sealedArgumentsDiffer(componentName, props.typeName(srcType), props.typeName(tgtType))
      );
    }
    for (final var side : List.of(srcType, tgtType)) {
      final var root = props.rawType(side);
      final var arguments = props.typeArguments(side);
      final var pending = new ArrayDeque<>(props.permittedSubclasses(root));
      while (!pending.isEmpty()) {
        final var caseType = pending.pop();
        pending.addAll(props.permittedSubclasses(caseType));
        final var gives = props.declaredArgumentsAs(caseType, root);
        for (int i = 0; i < gives.size(); i++) {
          final var given = gives.get(i);
          if (props.isTypeVariable(given)) continue;
          if (!props.mentionsTypeVariable(given) && props.sameType(given, arguments.get(i))) continue;
          return new PairDecision.Incompatible<>(
            PairingMessages.caseOutsideArguments(
              componentName,
              props.typeName(side),
              props.sourceName(caseType),
              argumentList(gives)
            )
          );
        }
      }
    }
    return new PairDecision.DispatchCases<>();
  }

  /** {@code arguments} as a type argument list is written: {@code <Integer, String>}. */
  private String argumentList(final List<T> arguments) {
    return "<" + String.join(", ", arguments.stream().map(props::typeName).toList()) + ">";
  }

  /**
   * {@code targetType}, a member type read off {@code targetClass}, with each type variable that
   * class declares read as the variable {@code sourceClass} declares in the same position. Two
   * generic classes paired with each other pass a variable across by position, so {@code record
   * BoxDto<U>(U v)} paired with {@code record Box<T>(T v)} reads its {@code v} as a {@code T} and
   * converts it unchanged, which is what converting a {@code Box<Integer>}, or the case of a sealed
   * {@code Res<Integer>}, needs. Nothing is read across between two classes that declare a
   * different number of type variables, or where a variable's bounds, read the same way, are not
   * its counterpart's. The target's member type is then returned as it is.
   */
  public T alignedToSource(final T targetType, final T sourceClass, final T targetClass) {
    final var sourceVariables = props.typeParameters(sourceClass);
    final var targetVariables = props.typeParameters(targetClass);
    if (targetVariables.isEmpty() || sourceVariables.size() != targetVariables.size()) return targetType;
    for (int i = 0; i < sourceVariables.size(); i++) {
      final var sourceBounds = props.upperBounds(sourceVariables.get(i));
      final var targetBounds = props.upperBounds(targetVariables.get(i));
      if (sourceBounds.size() != targetBounds.size()) return targetType;
      for (int b = 0; b < sourceBounds.size(); b++) {
        final var read = props.substitute(targetBounds.get(b), targetVariables, sourceVariables);
        if (!props.sameType(sourceBounds.get(b), read)) return targetType;
      }
    }
    return props.substitute(targetType, targetVariables, sourceVariables);
  }

  /**
   * Why a case of the sealed {@code sourceRoot} cannot convert through the case of {@code
   * targetRoot} it pairs with, or null when it can. Two generic roots declaring the same number of
   * type variables pass each across by position, so each case has to give its root the arguments
   * the paired case gives the other, with the target case's variables read as the source case's
   * ({@link #alignedToSource}). {@code record Ok<A, B>(A a, B b) implements Res<A, B>} paired with
   * {@code record OkDto<A, B>(A a, B b) implements ResDto<B, A>} gives them in the other order, and
   * a value converted through the pair would hold its {@code a} where its interface reads a {@code
   * B}. Roots without type variables, or with a different number of them, carry none across, so any
   * case pairing their cases make is left to the cases' own conversion.
   */
  public String caseArgumentsRefusal(final T sourceRoot, final T targetRoot, final T sourceCase, final T targetCase) {
    final var rootVariables = props.typeParameters(sourceRoot);
    if (rootVariables.isEmpty() || rootVariables.size() != props.typeParameters(targetRoot).size()) return null;
    final var sourceGives = props.declaredArgumentsAs(sourceCase, sourceRoot);
    final var targetGives = props.declaredArgumentsAs(targetCase, targetRoot);
    var same = sourceGives.size() == targetGives.size();
    for (int i = 0; same && i < sourceGives.size(); i++) {
      same = props.sameType(sourceGives.get(i), alignedToSource(targetGives.get(i), sourceCase, targetCase));
    }
    if (same) return null;
    return PairingMessages.caseArgumentsDiffer(
      props.sourceName(sourceRoot),
      props.sourceName(targetRoot),
      props.sourceName(sourceCase),
      props.sourceName(targetCase),
      argumentList(sourceGives),
      argumentList(targetGives)
    );
  }

  /**
   * Whether a pairing reads its two properties as declared rather than as members of their own
   * classes. Read as members, a property a generic superclass declares takes the type each subclass
   * gives it. A subclass that passes its own type variable through gives it that variable, which is
   * a different type on each side of a pair; read as declared, two subclasses of one superclass see
   * one type. So a pairing reads both sides as declared once either member type names a type
   * variable, and as members otherwise.
   */
  public boolean readsAsDeclared(final T sourceMember, final T targetMember) {
    return props.mentionsTypeVariable(sourceMember) || props.mentionsTypeVariable(targetMember);
  }

  /**
   * The types a source property and the target property it pairs with are read as, decided once for
   * the pair by {@link #readsAsDeclared}: each as a member of its own class, or both as declared.
   * Read as declared, the target's type reads each type variable {@code targetClass} declares as
   * the variable {@code sourceClass} declares in the same position ({@link #alignedToSource}).
   *
   * @param sourceMember the source property's type as a member of {@code sourceClass}, with each
   *     variable the class passes through kept as that variable
   * @param targetMember the target property's type as a member of {@code targetClass}, read the
   *     same way
   * @param sourceDeclared the source property's type as its accessor declares it
   * @param targetDeclared the target property's type as its accessor declares it
   */
  public PairedTypes<T> pairedTypes(
    final T sourceMember,
    final T targetMember,
    final T sourceDeclared,
    final T targetDeclared,
    final T sourceClass,
    final T targetClass
  ) {
    if (!readsAsDeclared(sourceMember, targetMember)) {
      return new PairedTypes<>(sourceMember, targetMember, sourceMember, targetMember, false);
    }
    final var target = alignedToSource(targetDeclared, sourceClass, targetClass);
    return new PairedTypes<>(
      sourceDeclared,
      target,
      sourceMember,
      targetMember,
      props.sameType(sourceDeclared, target)
    );
  }

  /**
   * What a value of a pair read as declared owes the side it is written into, given the type both
   * sides declare the property as, {@code declared}, and the type the value has as a member of the
   * side it is read from, {@code sendingMember}, and as a member of the side it is written into,
   * {@code receivingMember}. Each member type keeps the variables its class passes through, so
   * their bounds say what the side holds. Read as declared, the pair is one type on both sides and
   * the value is handed across unchanged, so what the receiving side's class says about the
   * property is the only thing that can refuse it.
   *
   * <p>Handed across unchanged, a value is either copied or shared. A container the allocation
   * table rebuilds is copied, shallowly ({@link #decidePair} on {@code declared} against itself),
   * and anything else, an {@code Optional}, an array and any other generic class included, is
   * handed across as the same object, which both sides then hold. A shared value has to be one the
   * receiving side's type admits as Java assigns it: its type arguments are the receiving side's,
   * or contained by a wildcard there, since a write through either side's type reaches the object
   * the other holds. A copy holds the sending side's own elements, so each of its arguments needs
   * every value the sending side's stands for to fit the receiving side's as a shared value. It
   * needs no more where the copy is a fresh container only the receiving side writes to, or an
   * unmodifiable source handed across as itself, which the receiving side cannot write through. A
   * copy that keeps the comparator the source orders its elements, or a map its keys, by is neither
   * for those: the comparator takes only the source's element type, so the argument it orders has
   * to be the sending side's own. A variable the receiving side passes through admits what its
   * bounds do, a bound given type arguments as a value both sides hold, and an array admits an
   * array whose component fits its own, as Java's arrays do.
   *
   * <p>A value that fits is not checked. Otherwise a type given arguments is refused, a container
   * and any other generic class alike, since nothing checks a value's arguments as it is written.
   * So is a variable with a bound given arguments that names no variable, and an array whose
   * component, once every array level is taken off, is given type arguments or is a variable: its
   * class carries only the erasure of that component. Any other value is checked against the
   * erasure of the receiving side's member type as it is written, unless every value of the sending
   * side's is already of that class; that includes an array of a class with no type arguments,
   * whose class carries its component whole.
   */
  public FixedFit<T> fixedFit(
    final T declared,
    final T sendingMember,
    final T receivingMember,
    final String componentName
  ) {
    final var copied = copiedAcross(declared);
    final var ordered = copied && mayKeepSourceOrder(declared);
    final var fits = copied
      ? fitsCopy(sendingMember, receivingMember, ordered)
      : fitsShared(sendingMember, receivingMember);
    if (fits) return new FixedFit.Fits<>();
    if (ordered && fitsCopy(sendingMember, receivingMember, false)) {
      return new FixedFit.Refused<>(
        FixedFit.Reason.ORDERED_COPY,
        PairingMessages.orderedCopyArgumentDiffers(
          componentName,
          props.typeName(sendingMember),
          props.typeName(receivingMember)
        )
      );
    }
    if (
      containerViewOf(receivingMember) != null ||
      !props.typeArguments(receivingMember).isEmpty() ||
      genericComponent(receivingMember) ||
      boundGivenArguments(receivingMember)
    ) {
      return new FixedFit.Refused<>(
        FixedFit.Reason.ARGUMENTS,
        PairingMessages.fixedArgumentsDoNotFit(
          componentName,
          props.typeName(sendingMember),
          props.typeName(receivingMember)
        )
      );
    }
    final var fixed = props.erasure(receivingMember);
    return props.isAssignable(sendingMember, fixed) ? new FixedFit.Fits<>() : new FixedFit.Check<>(fixed);
  }

  /**
   * Whether {@code type} is an array whose component, once every array level is taken off, is given
   * type arguments or is a variable, so the array's class does not carry it whole.
   */
  private boolean genericComponent(final T type) {
    var component = props.componentType(type);
    if (component == null) return false;
    while (props.componentType(component) != null) component = props.componentType(component);
    return props.isTypeVariable(component) || !props.typeArguments(component).isEmpty();
  }

  /**
   * Whether a value of the declared type {@code declared}, handed across unchanged, arrives as a
   * copy rather than as the object the sending side holds: what {@link #decidePair} decides for the
   * declared type paired with itself.
   */
  private boolean copiedAcross(final T declared) {
    return !(unchanged(declared) instanceof PairDecision.Identity<T>);
  }

  /**
   * Whether a container of type {@code sending}, copied, fits {@code receiving}: the copy holds the
   * sending side's elements, so each argument of the receiving side's has to admit every value the
   * sending side's stands for, as that value is shared ({@link #fitsArgument}). Where the copy can
   * keep the comparator the source orders by, {@code ordered}, the argument it orders, the first of
   * every class the allocation table names, has to be the sending side's own, since the comparator
   * takes only values of that type.
   */
  private boolean fitsCopy(final T sending, final T receiving, final boolean ordered) {
    if (fitsShared(sending, receiving)) return true;
    final var receivingArguments = props.typeArguments(receiving);
    final var sendingArguments = props.typeArguments(sending);
    if (receivingArguments.isEmpty() || sendingArguments.size() != receivingArguments.size()) return false;
    if (!props.sameType(props.rawType(sending), props.rawType(receiving))) return false;
    for (int i = 0; i < receivingArguments.size(); i++) {
      final var copy = !(ordered && i == 0);
      if (!fitsArgument(sendingArguments.get(i), receivingArguments.get(i), copy)) return false;
    }
    return true;
  }

  /**
   * The JDK containers whose copy keeps the comparator the source orders its elements, or a map its
   * keys, by.
   */
  private static final List<String> ORDER_KEEPING = List.of(
    "java.util.TreeSet",
    "java.util.concurrent.ConcurrentSkipListSet",
    "java.util.PriorityQueue",
    "java.util.concurrent.PriorityBlockingQueue",
    "java.util.TreeMap",
    "java.util.concurrent.ConcurrentSkipListMap"
  );

  /**
   * Whether the copy of a value declared as {@code declared} can keep the comparator the source
   * orders by. A copy of a JDK container keeps the container's class, comparator included, so it
   * can wherever the declared type holds one of the containers that keep one: a {@code Set} or a
   * {@code Collection} can hold a {@code TreeSet}, a {@code Queue} a {@code PriorityQueue}, and a
   * {@code Map} a {@code TreeMap}.
   */
  private boolean mayKeepSourceOrder(final T declared) {
    final var raw = props.rawType(declared);
    return ORDER_KEEPING.stream().anyMatch(name -> props.isImplementedBy(raw, name));
  }

  /**
   * Whether every value of {@code sending} fits {@code receiving} while both sides hold it. A
   * variable on the receiving side admits what its bounds do ({@link #fitsVariable}). An array fits
   * an array whose component its own component fits. Where the two are the same class their type
   * arguments are compared one by one, each contained by the receiving side's ({@link
   * #fitsArgument}).
   */
  private boolean fitsShared(final T sending, final T receiving) {
    if (props.isTypeVariable(receiving)) return fitsVariable(sending, receiving);
    if (props.isAssignable(sending, receiving)) return true;
    final var receivingComponent = props.componentType(receiving);
    if (receivingComponent != null) {
      final var sendingComponent = props.componentType(sending);
      return sendingComponent != null && fitsShared(sendingComponent, receivingComponent);
    }
    final var receivingArguments = props.typeArguments(receiving);
    final var sendingArguments = props.typeArguments(sending);
    if (receivingArguments.isEmpty() || sendingArguments.size() != receivingArguments.size()) return false;
    if (!props.sameType(props.rawType(sending), props.rawType(receiving))) return false;
    for (int i = 0; i < receivingArguments.size(); i++) {
      if (!fitsArgument(sendingArguments.get(i), receivingArguments.get(i), false)) return false;
    }
    return true;
  }

  /**
   * Whether every value of {@code sending} fits the variable {@code receiving}, which the receiving
   * side passes through: its erasure, and every bound given type arguments that names no variable,
   * as a value both sides hold. A bound naming a variable, such as {@code Comparable<Y>}, says what
   * a value is only against the side's other values of that variable, which one value does not
   * show, so only its erasure is asked of it.
   */
  private boolean fitsVariable(final T sending, final T receiving) {
    if (!props.isAssignable(sending, props.erasure(receiving))) return false;
    for (final var bound : props.upperBounds(receiving)) {
      if (givenArgumentsNamingNoVariable(bound) && !fitsShared(sending, bound)) return false;
    }
    return true;
  }

  /**
   * Whether {@code type} is a variable with a bound given type arguments that names no variable,
   * which a check of a value's class cannot show it fits.
   */
  private boolean boundGivenArguments(final T type) {
    return (
      props.isTypeVariable(type) && props.upperBounds(type).stream().anyMatch(this::givenArgumentsNamingNoVariable)
    );
  }

  /** Whether {@code type} is given type arguments and names no type variable. */
  private boolean givenArgumentsNamingNoVariable(final T type) {
    return !props.typeArguments(type).isEmpty() && !props.mentionsTypeVariable(type);
  }

  /**
   * Whether the type argument {@code sending} names the type the type argument {@code receiving},
   * which is no wildcard, names: the same type, or one that differs from it only where {@code
   * receiving} names a variable the receiving side passes through, which admits what its bounds do
   * ({@link #fitsVariable}). A wildcard names no one type, and a variable of the sending side's is
   * no type but itself.
   */
  private boolean namesTheSameType(final T sending, final T receiving) {
    if (props.isTypeVariable(receiving)) return fitsVariable(valuesOf(sending), receiving);
    if (props.isWildcard(sending)) return false;
    if (props.sameType(sending, receiving)) return true;
    final var receivingComponent = props.componentType(receiving);
    if (receivingComponent != null) {
      final var sendingComponent = props.componentType(sending);
      return sendingComponent != null && namesTheSameType(sendingComponent, receivingComponent);
    }
    final var receivingArguments = props.typeArguments(receiving);
    final var sendingArguments = props.typeArguments(sending);
    if (receivingArguments.isEmpty() || sendingArguments.size() != receivingArguments.size()) return false;
    if (!props.sameType(props.rawType(sending), props.rawType(receiving))) return false;
    for (int i = 0; i < receivingArguments.size(); i++) {
      if (!fitsArgument(sendingArguments.get(i), receivingArguments.get(i), false)) return false;
    }
    return true;
  }

  /**
   * Whether a value of type {@code sending}, written into a member whose parameter is {@code
   * receiving} once a check of its class has passed, can be shown to have the type arguments the
   * parameter fixes. A cast sees only a value's class, so its arguments have to be decided here.
   *
   * <p>A parameter fixing no arguments, or only unbounded wildcards, asks nothing of them.
   * Otherwise the two types are related by their classes and their arguments compared one by one,
   * as Java's containment of type arguments decides it ({@link #fitsArgument}): a result of the
   * parameter's class directly; one of a subclass seen as the parameter's class, as {@code
   * ArrayList<String>} gives {@code List} a {@code String}; and one of a superclass against the
   * parameter seen as that class, as {@code List<String>} gives {@code Collection} a {@code
   * String}. A class declaring no type parameters is seen the same way, through the arguments it
   * fixes on its supertypes, as {@code class Names extends ArrayList<String>} gives {@code List} a
   * {@code String}. A generic class used raw gives no arguments, and a result of a class neither
   * extends, {@code Object} included, cannot be shown to fit.
   *
   * <p>The result is written as it is, and nothing shows that only the receiving side holds it: a
   * conversion may return its argument, or any value something else keeps. So an argument that is
   * no wildcard contains only one naming the same type, as for any value two holders share, and a
   * {@code List<Integer>} result does not fit a {@code List<Number>} parameter.
   */
  public boolean argumentsFit(final T sending, final T receiving) {
    final var receivingArguments = props.typeArguments(receiving);
    if (receivingArguments.stream().allMatch(this::unboundedWildcard)) return true;
    final var sendingArguments = props.typeArguments(sending);
    final var sendingClass = props.rawType(sending);
    // A generic class used raw gives no arguments at all; a class declaring no type parameters
    // fixes those of its supertypes, which the relation below reads.
    if (sendingArguments.isEmpty() && !props.typeParameters(sendingClass).isEmpty()) return false;
    final var receivingClass = props.rawType(receiving);
    final List<T> given;
    final List<T> wanted;
    if (props.sameType(sendingClass, receivingClass)) {
      given = sendingArguments;
      wanted = receivingArguments;
    } else if (props.isAssignable(sendingClass, receivingClass)) {
      given = seenAs(sending, receivingClass);
      wanted = receivingArguments;
    } else if (props.isAssignable(receivingClass, sendingClass)) {
      given = sendingArguments;
      wanted = seenAs(receiving, sendingClass);
    } else {
      return false;
    }
    if (given.isEmpty() || given.size() != wanted.size()) return false;
    for (int i = 0; i < wanted.size(); i++) {
      if (!fitsArgument(given.get(i), wanted.get(i), false)) return false;
    }
    return true;
  }

  /** Whether {@code argument} is {@code ?}, bounded neither above nor below. */
  private boolean unboundedWildcard(final T argument) {
    return props.isWildcard(argument) && props.upperBound(argument) == null && props.lowerBound(argument) == null;
  }

  /**
   * The type arguments {@code type} gives its supertype {@code supertype}: what its class declares
   * it gives, with the variables the class declares read as {@code type}'s arguments, and those of
   * each class it is nested in as the arguments its enclosing type gives that class, at every
   * level. {@code Outer<String>.In}, with {@code class In extends ArrayList<T>} inside {@code
   * Outer<T>}, gives {@code List} a {@code String}.
   */
  private List<T> seenAs(final T type, final T supertype) {
    final var variables = new ArrayList<T>();
    final var arguments = new ArrayList<T>();
    for (var level = type; level != null; level = props.enclosingType(level)) {
      final var declared = props.typeParameters(props.rawType(level));
      final var given = props.typeArguments(level);
      if (given.size() != declared.size()) continue;
      variables.addAll(declared);
      arguments.addAll(given);
    }
    return props
      .declaredArgumentsAs(props.rawType(type), supertype)
      .stream()
      .map(argument -> props.substitute(argument, variables, arguments))
      .toList();
  }

  /**
   * Whether the type argument {@code receiving} admits what the type argument {@code sending}
   * stands for. A wildcard decides it as Java's containment of type arguments does: an unbounded
   * one contains anything; {@code ? extends B} contains an argument whose values all fit {@code B};
   * and {@code ? super B} contains an argument every {@code B} is a value of, which only one naming
   * a type with no variable in it, or a wildcard bounded below by one, can show. A sending wildcard
   * stands for values of its upper bound, {@code Object} where it declares none.
   *
   * <p>Any other argument admits, in the arguments of a {@code copy} built for the receiving side,
   * an argument whose values all fit it, since the copy is a fresh container only that side holds.
   * In the arguments of a value both sides hold, it contains only an argument naming the same type
   * ({@link #namesTheSameType}): a {@code Box<Integer>} held as a {@code Box<Number>} too would
   * take a {@code Double} through the one and read it as an {@code Integer} through the other.
   */
  private boolean fitsArgument(final T sending, final T receiving, final boolean copy) {
    if (props.isWildcard(receiving)) {
      final var lower = props.lowerBound(receiving);
      if (lower != null) return everyValueIsOf(lower, sending);
      final var upper = props.upperBound(receiving);
      return upper == null || fitsShared(valuesOf(sending), upper);
    }
    return copy ? fitsShared(valuesOf(sending), receiving) : namesTheSameType(sending, receiving);
  }

  /** The type the values a type argument stands for are of: a wildcard's upper bound, or itself. */
  private T valuesOf(final T argument) {
    if (!props.isWildcard(argument)) return argument;
    final var upper = props.upperBound(argument);
    return upper == null ? props.typeNamed("java.lang.Object") : upper;
  }

  /**
   * Whether every value of {@code lower} is a value of the type argument {@code sending}: a type
   * that names no variable and that {@code lower} is assignable to, or a wildcard bounded below by
   * one. A variable, or a wildcard bounded above, names no one type every value of {@code lower} is
   * shown to be.
   */
  private boolean everyValueIsOf(final T lower, final T sending) {
    if (props.isWildcard(sending)) {
      final var sendingLower = props.lowerBound(sending);
      return sendingLower != null && everyValueIsOf(lower, sendingLower);
    }
    return !props.mentionsTypeVariable(sending) && props.isAssignable(lower, sending);
  }

  /** Whether both sides are enums. A pair of one enum is the same type and passes through. */
  public boolean bothEnums(final T srcType, final T tgtType) {
    return props.isEnumType(srcType) && props.isEnumType(tgtType);
  }

  /** The names of an enum's constants in declaration order. */
  public List<String> enumConstants(final T enumType) {
    return props.enumConstants(enumType);
  }

  /** How the constants of two enums line up by name. */
  public EnumCorrespondence enumCorrespondence(final T srcType, final T tgtType) {
    return EnumCorrespondence.of(props.enumConstants(srcType), props.enumConstants(tgtType));
  }

  /**
   * What a pair whose two sides are one declared type takes.
   *
   * <p>A container declared as a type the allocation table rebuilds is copied, so a converted
   * target never holds the source's own mutable container. The copy is shallow: its elements, or a
   * map's keys and values, are the source's own. Which class the copy is built as is the consumer's
   * to decide from the value, since the declared type does not say which class the source is; the
   * table's answer for the declared type is what a class that cannot be built falls back to.
   * Anything else passes through as itself: a scalar, a record, an {@code Optional}, and a
   * container declared as a class the table does not name.
   *
   * <p>A map whose key names a type variable is copied too. Two key types that hold variables
   * cannot be shown to be the same type, which is why {@link #containerViewOf} gives such a map no
   * view, but a pair of one declared type has one key type, carried across unchanged.
   */
  private PairDecision<T> unchanged(final T type) {
    final var own = containerViewOf(type);
    final var view = settledAgainst(own == null ? mapKeyedByVariable(type) : own, null);
    if (view == null || view.kind() == ContainerView.Kind.OPTIONAL) return new PairDecision.Identity<>();
    if (!(allocationFor(type, view.kind()) instanceof Allocation.Build)) return new PairDecision.Identity<>();
    return view.kind() == ContainerView.Kind.MAP_VALUES
      ? new PairDecision.MapCopy<>()
      : new PairDecision.CollectionCopy<>();
  }

  /**
   * The view of {@code type} as a map whose key names a type variable and is no wildcard, or null
   * when it is not one. A generic class used raw gives no arguments, and so no view.
   */
  private ContainerView<T> mapKeyedByVariable(final T type) {
    final var raw = props.rawType(type);
    if (props.typeArguments(type).isEmpty() || !props.isSubtypeOf(raw, WellKnown.MAP)) return null;
    final var arguments = props.typeArgumentsAs(type, WellKnown.MAP);
    if (arguments.size() != 2) return null;
    final var key = arguments.getFirst();
    if (props.isWildcard(key) || !props.mentionsTypeVariable(key)) return null;
    return new ContainerView<>(ContainerView.Kind.MAP_VALUES, arguments.get(1), key, raw);
  }

  /**
   * Whether a sorted set target's elements, converted from the source's, are of a class nothing can
   * order. A comparator the source carries orders the type being converted away from and cannot
   * come across, so the rebuild orders by the element's own {@code compareTo}, and an element class
   * that does not implement {@code Comparable} fails on the first insert of every conversion. An
   * interface or wildcard element is let through, since the values a conversion produces for it may
   * be comparable. Asked only once the element pair is known to convert, so a pairing refused for
   * another reason keeps that reason.
   */
  public boolean unorderableSortedTarget(final T srcElement, final T tgtElement, final T tgtType) {
    if (!props.isSubtypeOf(props.rawType(tgtType), WellKnown.SORTED_SET)) return false;
    if (props.sameType(srcElement, tgtElement)) return false;
    if (props.isWildcard(tgtElement)) return false;
    final var element = props.rawType(tgtElement);
    if (props.isInterfaceType(element)) return false;
    return !props.isSubtypeOf(element, WellKnown.COMPARABLE);
  }

  /**
   * Whether a sorted map target is built over keys of a class nothing can order, with no way to be
   * handed one. Keys pass through a conversion unchanged, so a comparator the source carries could
   * order them, but only where the target's class can be told it: a declared interface is built as
   * the family default, which can, and a class can when the shared rules carry an ordering into it.
   * Where it cannot, the rebuild orders by the key's own {@code compareTo}.
   *
   * <p>A key that passes through unchanged can be any subtype of its declared type, and a subtype
   * may implement {@code Comparable} where its declared type does not, as a {@code String} key does
   * in a map declared over {@code Object}. So only a key type that can have no subtypes decides: a
   * record or an array that does not implement {@code Comparable} fails on the first insert of
   * every non-empty conversion. Every other key, an interface, a wildcard, a variable or a class
   * open to subclassing, is let through, and a key it admits that cannot be ordered is refused by
   * name when it is inserted.
   */
  public boolean unorderableSortedKeys(final T keyType, final T tgtType) {
    final var raw = props.rawType(tgtType);
    if (!props.isSubtypeOf(raw, WellKnown.SORTED_MAP)) return false;
    final var key = props.rawType(keyType);
    if (!props.isRecordType(key) && !props.isArrayType(key)) return false;
    if (props.isSubtypeOf(key, WellKnown.COMPARABLE)) return false;
    if (props.isInterfaceType(raw)) return false;
    return !(orderingFor(tgtType, raw, ContainerView.Kind.MAP_VALUES, true) instanceof Ordering.Carry<T>);
  }

  private static final Set<WellKnown> GENERAL = Set.of(WellKnown.DEQUE, WellKnown.QUEUE, WellKnown.COLLECTION);

  private static final Map<WellKnown, String> GENERAL_NAMES = Map.of(
    WellKnown.DEQUE,
    "java.util.Deque",
    WellKnown.QUEUE,
    "java.util.Queue",
    WellKnown.COLLECTION,
    "java.util.Collection"
  );

  /**
   * A view seen as the other side's kind when it has none of its own. A {@code COLLECTION} against
   * a list or a set becomes that; against another {@code COLLECTION}, or against nothing, it
   * becomes a list.
   *
   * <p>Part of the classification rather than of one consumer's plumbing: a {@code COLLECTION} view
   * names no shape, so anything that pairs two views owes this step before it can ask whether the
   * two kinds are the same. Both the runtime lift and the generated one consume it, and a consumer
   * that skips it sees a kind no lift can build.
   */
  public ContainerView<T> settledAgainst(final ContainerView<T> view, final ContainerView<T> other) {
    if (view == null || view.kind() != ContainerView.Kind.COLLECTION) return view;
    final var against = other == null ? null : other.kind();
    return view.as(
      against == ContainerView.Kind.LIST || against == ContainerView.Kind.SET ? against : ContainerView.Kind.LIST
    );
  }

  /**
   * Whether a comparator over {@code ordered} can be handed to a constructor parameter of type
   * {@code comparatorParam}, which is resolved against the arguments the field gave its container.
   *
   * <p>It can exactly when the parameter orders a supertype of {@code ordered}. That covers the
   * wildcard the JDK declares, the type variable itself, and a named supertype such as {@code
   * Object}. A parameter over an unrelated class, or over a type variable the field bound to
   * something else, erases to the same constructor and accepts nothing the source carries, so the
   * question is asked of the resolved parameter rather than of the constructor reflection binds. A
   * raw parameter holds any comparator.
   */
  public boolean canOrder(final T comparatorParam, final T ordered) {
    final var arguments = props.typeArguments(comparatorParam);
    if (arguments.isEmpty()) return true;
    final var argument = arguments.getFirst();
    if (props.isWildcard(argument)) {
      // Only a lower bound narrows what the parameter accepts; an upper bound or none does not.
      final var lower = props.lowerBound(argument);
      return lower == null || props.isAssignable(ordered, lower);
    }
    return props.isAssignable(ordered, argument);
  }

  /**
   * How a container declared as {@code declared}, built as {@code impl}, is told the order its
   * source kept. {@code kind} is the family it is built as, and {@code elementsPreserved} whether
   * its elements, or a map's keys, are the source's own rather than converted.
   *
   * <p>A container that keeps no order has nothing to be told. A sorted set whose elements are
   * converted cannot take the source's comparator, which orders the type converted away from. Any
   * other sorted container takes it through its comparator constructor, when it has one this world
   * can call whose parameter can hold a comparator over what the field orders: a map's key type, a
   * set's element type. That parameter is resolved against the arguments the field gave the class
   * built, which are the declared type's own when it is that class, so a subtype declaring its
   * parameters in another order than {@code Map} resolves correctly, and the container's otherwise.
   *
   * <p>A declaration used raw constrains no comparator: whatever the source was ordered by orders
   * the elements copied from it. Its arguments read as the class's own type variables in one world
   * and as none in the other, and either way the parameter is read over the built class's own type
   * variables, so the only question left is whether that class has a comparator constructor this
   * world can call. A raw interface such as {@code SortedSet} is answered by the class it is built
   * as.
   */
  public Ordering<T> orderingFor(
    final T declared,
    final T impl,
    final ContainerView.Kind kind,
    final boolean elementsPreserved
  ) {
    final var map = kind == ContainerView.Kind.MAP_VALUES;
    if (kind != ContainerView.Kind.SET && !map) return new Ordering.None<>();
    final var raw = props.rawType(impl);
    if (!props.isSubtypeOf(raw, map ? WellKnown.SORTED_MAP : WellKnown.SORTED_SET)) return new Ordering.None<>();
    if (!map && !elementsPreserved) return new Ordering.Refuse<>(PairingMessages.comparatorAcrossConversion());
    final Ordering<T> refused = new Ordering.Refuse<>(PairingMessages.noComparatorConstructor(props.sourceName(raw)));
    final var arguments = props.typeArgumentsAs(declared, map ? WellKnown.MAP : WellKnown.SET);
    if (arguments.isEmpty()) {
      final var parameter = props.comparatorParameter(raw, List.of());
      return parameter == null ? refused : new Ordering.Carry<>(parameter);
    }
    final var bindings = props.sameType(props.rawType(declared), raw) ? props.typeArguments(declared) : arguments;
    final var parameter = props.comparatorParameter(raw, bindings);
    if (parameter == null || !canOrder(parameter, arguments.getFirst())) return refused;
    return new Ordering.Carry<>(parameter);
  }

  /**
   * The container view of {@code t}, or {@code null} when {@code t} is not a container the
   * auto-lift understands with element types it can name. A class that declares no type parameters
   * of its own is viewed through the supertype that fixes them; a generic class used raw has none
   * to name and presents no view. Selection rules: {@code Optional} (final, exact) → OPTIONAL; any
   * {@code List} subtype, and the {@code Deque} and {@code Queue} interfaces by name → LIST; any
   * {@code Set} subtype → SET; any {@code Map} subtype whose key type is one {@link #decidableKey}
   * accepts → MAP_VALUES; the {@code Collection} interface by name → COLLECTION, which names no
   * shape and is settled against the other side of the pair by {@link #settledAgainst}.
   */
  public ContainerView<T> containerViewOf(final T t) {
    // A container subtype is viewed through its container supertype rather than through its own
    // parameters, which can be reordered, fixed, or unrelated to the element and key types.
    final var declaresNoArguments = props.typeArguments(t).isEmpty();
    final var raw = props.rawType(t);
    // Order decides the answer where a type satisfies more than one: a List is asked as a List
    // before it is asked as a Collection. The last three are what a type reaches only by being
    // none of the others -- a Deque, a Queue, or a field declared as the general Collection -- and
    // they are viewed as lists because that is what they keep: an order, and duplicates.
    for (final var kind : List.of(
      WellKnown.OPTIONAL,
      WellKnown.LIST,
      WellKnown.SET,
      WellKnown.MAP,
      WellKnown.DEQUE,
      WellKnown.QUEUE,
      WellKnown.COLLECTION
    )) {
      if (!props.isSubtypeOf(raw, kind)) continue;
      // The three general kinds are matched by name rather than by subtype. A concrete queue may
      // be capacity-bounded -- a SynchronousQueue holds nothing at all -- and accepting one turns
      // a refusal while the plan is built into a failure on every conversion, which is the worse
      // of the two. A field declared as one of the interfaces asks only for something that keeps
      // an order, and that is answerable.
      if (GENERAL.contains(kind) && !props.typeName(raw).equals(GENERAL_NAMES.get(kind))) continue;
      final var args = props.typeArgumentsAs(t, kind);
      // A type written with no arguments of its own is one of two unlike things, and the supertype
      // view tells them apart. A class that declares no type parameters fixes its element types on
      // its supertype, so they resolve there to types, which may themselves be parameterized, as
      // List<Leaf> and List<?> are. A generic class used raw leaves its parameters unbound, so what
      // resolves is a type variable, or nothing: nobody has said what its elements are, and it
      // presents no view.
      if (declaresNoArguments && args.stream().anyMatch(props::mentionsTypeVariable)) return null;
      if (kind == WellKnown.MAP) {
        if (args.size() != 2 || !decidableKey(args.getFirst())) return null;
        return new ContainerView<>(ContainerView.Kind.MAP_VALUES, args.get(1), args.getFirst(), raw);
      }
      if (args.size() != 1) return null;
      return new ContainerView<>(
        switch (kind) {
          case OPTIONAL -> ContainerView.Kind.OPTIONAL;
          // A Deque and a Queue keep an order and admit duplicates, which is what a list is. A
          // field declared as the general Collection has said neither, so it is settled against
          // whatever the other side of the pair turns out to be.
          case LIST, DEQUE, QUEUE -> ContainerView.Kind.LIST;
          case COLLECTION -> ContainerView.Kind.COLLECTION;
          case SET -> ContainerView.Kind.SET;
          default -> throw new AssertionError(kind);
        },
        args.getFirst(),
        null,
        raw
      );
    }
    return null;
  }

  /**
   * A record, or any class the reflective bean machinery can decompose — everything except
   * primitives, arrays, enums, interfaces, and the common scalar families ({@code CharSequence},
   * {@code Number}, {@code Boolean}/{@code Character}, {@code Temporal}, {@code UUID}).
   */
  public boolean reflectable(final T t) {
    if (props.isRecordType(t)) return true;
    if (props.isPrimitive(t)) return false;
    if (props.isArrayType(t)) return false;
    if (props.isEnumType(t)) return false;
    if (props.isInterfaceType(t)) return false;
    if (props.isSubtypeOf(t, WellKnown.CHAR_SEQUENCE)) return false;
    if (props.isSubtypeOf(t, WellKnown.NUMBER)) return false;
    if (props.isSubtypeOf(t, WellKnown.BOOLEAN_WRAPPER) || props.isSubtypeOf(t, WellKnown.CHARACTER_WRAPPER)) {
      return false;
    }
    if (props.isSubtypeOf(t, WellKnown.TEMPORAL)) return false;
    return !props.isSubtypeOf(t, WellKnown.UUID);
  }

  /**
   * How a value of {@code property}, a bean property's read type, reaches {@code parameter}, the
   * write type its setter takes. Both are read by their erasure. See {@link SetterFit}.
   */
  public SetterFit setterFit(final T property, final T parameter) {
    final var read = props.rawType(property);
    final var write = props.rawType(parameter);
    if (props.sameType(props.boxed(read), props.boxed(write))) return SetterFit.SAME;
    if (props.isPrimitive(write)) {
      final var scalar = props.isPrimitive(read) ? props.typeName(read) : UNBOXED.get(props.typeName(read));
      if (scalar != null && WIDENS.getOrDefault(scalar, Set.of()).contains(props.typeName(write))) {
        return SetterFit.CONVERTS;
      }
      // A reference read type that a boxed value of the parameter's type is an instance of, such
      // as Number or Object, may hold a value the setter can take once unboxed.
      return !props.isPrimitive(read) && props.isAssignable(props.boxed(write), read)
        ? SetterFit.NARROWS
        : SetterFit.DISJOINT;
    }
    // A boxed read value is an instance of its final wrapper class and of nothing narrower.
    if (props.isPrimitive(read)) {
      return props.isAssignable(props.boxed(read), write) ? SetterFit.CONVERTS : SetterFit.DISJOINT;
    }
    if (props.isAssignable(read, write)) return SetterFit.CONVERTS;
    if (props.isAssignable(write, read) || props.isInterfaceType(read) || props.isInterfaceType(write)) {
      return SetterFit.NARROWS;
    }
    return SetterFit.DISJOINT;
  }

  /**
   * How a value of {@code property} reaches a setter taking {@code parameter} that is variable
   * arity over {@code component}, or not when {@code component} is null. A variable-arity setter
   * Java binds a single value to by its component, wrapping the value in a one-element array, takes
   * that value as its component's fit; otherwise the fit is the parameter's own.
   */
  public SetterFit setterFit(final T property, final T parameter, final T component) {
    final var direct = setterFit(property, parameter);
    if (component == null || direct.compiles()) return direct;
    final var spread = setterFit(property, component);
    return spread.compiles() ? spread : direct;
  }

  /**
   * Whether a value of {@code property} reaches a setter taking {@code parameter} only as the one
   * element of its variable-arity {@code component} array, so a reflective call wraps the value in
   * a one-element array and a generated call passes it as it is.
   */
  public boolean spreads(final T property, final T parameter, final T component) {
    return (
      component != null && !setterFit(property, parameter).compiles() && setterFit(property, component).compiles()
    );
  }

  /** {@link #chooseSetter(Object, List, List)} over methods none of which is variable arity. */
  public int chooseSetter(final T property, final List<T> parameters) {
    return chooseSetter(property, parameters, Collections.nCopies(parameters.size(), null));
  }

  /**
   * Which of the methods named after a property's setter is its setter, by its index in {@code
   * parameters}, the parameter type of each; {@code -1} when none can be chosen. {@code components}
   * holds, at the same index, the component type of a variable-arity method's array parameter, and
   * null for any other method. A sole method is the setter whatever it takes. Among several, the
   * choice is the overload Java binds a value of {@code property} to, so a generated call and a
   * reflective one reach the same method: the methods that take the value without boxing or
   * unboxing are asked first, then those that take it with either, then variable-arity methods
   * whose component takes it, and among those asked the one whose parameter every other one's
   * accepts. A parameter equal to the property's type is that one whenever it is present. None
   * taking the value, or no single most specific one, is {@code -1}, and the paths refuse the
   * property by name. The answer does not depend on the order the methods are listed in.
   */
  public int chooseSetter(final T property, final List<T> parameters, final List<T> components) {
    if (parameters.size() == 1) return 0;
    final var read = props.rawType(property);
    final var strict = new ArrayList<Integer>();
    final var loose = new ArrayList<Integer>();
    final var variable = new ArrayList<Integer>();
    for (var i = 0; i < parameters.size(); i++) {
      final var write = props.rawType(parameters.get(i));
      if (passesStrictly(read, write)) strict.add(i);
      if (setterFit(read, write).compiles()) loose.add(i);
      final var component = components.get(i);
      if (component != null && setterFit(read, props.rawType(component)).compiles()) variable.add(i);
    }
    if (!strict.isEmpty()) return mostSpecific(strict, parameters);
    if (!loose.isEmpty()) return mostSpecific(loose, parameters);
    return variable.isEmpty() ? -1 : mostSpecific(variable, components);
  }

  /**
   * The one index in {@code applicable} whose type in {@code types} every other one's accepts
   * without boxing, or {@code -1} when there is no single one.
   */
  private int mostSpecific(final List<Integer> applicable, final List<T> types) {
    var chosen = -1;
    for (final var i : applicable) {
      final var mine = props.rawType(types.get(i));
      final var most = applicable
        .stream()
        .allMatch(j -> j.equals(i) || passesStrictly(mine, props.rawType(types.get(j))));
      if (!most) continue;
      if (chosen >= 0) return -1;
      chosen = i;
    }
    return chosen;
  }

  /**
   * Whether a value of {@code from} is passed to {@code to} without boxing or unboxing: the same
   * type, a primitive widening, or a reference widening.
   */
  private boolean passesStrictly(final T from, final T to) {
    if (props.sameType(from, to)) return true;
    if (props.isPrimitive(from) != props.isPrimitive(to)) return false;
    if (props.isPrimitive(from)) {
      return WIDENS.getOrDefault(props.typeName(from), Set.of()).contains(props.typeName(to));
    }
    return props.isAssignable(from, to);
  }

  /** The primitive each wrapper unboxes to, by type name. */
  private static final Map<String, String> UNBOXED = Map.of(
    "java.lang.Boolean",
    "boolean",
    "java.lang.Byte",
    "byte",
    "java.lang.Short",
    "short",
    "java.lang.Character",
    "char",
    "java.lang.Integer",
    "int",
    "java.lang.Long",
    "long",
    "java.lang.Float",
    "float",
    "java.lang.Double",
    "double"
  );

  /** The widening primitive conversions, by type name. */
  private static final Map<String, Set<String>> WIDENS = Map.of(
    "byte",
    Set.of("short", "int", "long", "float", "double"),
    "short",
    Set.of("int", "long", "float", "double"),
    "char",
    Set.of("int", "long", "float", "double"),
    "int",
    Set.of("long", "float", "double"),
    "long",
    Set.of("float", "double"),
    "float",
    Set.of("double")
  );

  /** Primitive ↔ wrapper pair over the same scalar, in either direction. */
  public boolean primitiveWrapperPair(final T src, final T tgt) {
    return (
      (props.isPrimitive(src) && props.sameType(props.boxed(src), tgt)) ||
      (props.isPrimitive(tgt) && props.sameType(props.boxed(tgt), src))
    );
  }

  /**
   * Both sides are {@code Collection} subtypes that agree on every kind discriminator: the List
   * axis, the Set / SortedSet axis, and (within the Queue residual) the Deque axis. Disagreement on
   * any axis would silently re-interpret container semantics — or throw at copy time — so the pair
   * is rejected here and the user declares an explicit conversion row instead.
   */
  public boolean sameKindCollection(final T a, final T b) {
    if (!props.isSubtypeOf(a, WellKnown.COLLECTION) || !props.isSubtypeOf(b, WellKnown.COLLECTION)) return false;
    final var aList = props.isSubtypeOf(a, WellKnown.LIST);
    final var bList = props.isSubtypeOf(b, WellKnown.LIST);
    if (aList != bList) return false;
    if (aList) return true;
    final var aSet = props.isSubtypeOf(a, WellKnown.SET);
    final var bSet = props.isSubtypeOf(b, WellKnown.SET);
    if (aSet != bSet) return false;
    if (aSet) return props.isSubtypeOf(a, WellKnown.SORTED_SET) == props.isSubtypeOf(b, WellKnown.SORTED_SET);
    if (!props.isSubtypeOf(a, WellKnown.QUEUE) || !props.isSubtypeOf(b, WellKnown.QUEUE)) return false;
    return props.isSubtypeOf(a, WellKnown.DEQUE) == props.isSubtypeOf(b, WellKnown.DEQUE);
  }

  /** Whether each side is a {@code Collection} or a {@code Map}, of the same kind or not. */
  private boolean bothContainers(final T a, final T b) {
    return isContainer(a) && isContainer(b);
  }

  private boolean isContainer(final T t) {
    return props.isSubtypeOf(t, WellKnown.COLLECTION) || props.isSubtypeOf(t, WellKnown.MAP);
  }

  /**
   * Both sides are {@code Map} subtypes agreeing on the SortedMap axis — a {@code HashMap ↔
   * TreeMap} pair over non-Comparable keys would throw at copy time when the fresh sorted map calls
   * {@code compareTo} on the first inserted key, so the crossing is rejected before any conversion
   * runs.
   */
  public boolean sameKindMap(final T a, final T b) {
    if (!props.isSubtypeOf(a, WellKnown.MAP) || !props.isSubtypeOf(b, WellKnown.MAP)) return false;
    return props.isSubtypeOf(a, WellKnown.SORTED_MAP) == props.isSubtypeOf(b, WellKnown.SORTED_MAP);
  }

  /** What two same-kind containers' element types, and key types for a map, say about a copy. */
  private enum ElementMatch {
    /**
     * Carrying the elements across unchanged keeps every one of them of a type the target holds.
     */
    SAME,
    /** Both sides name their types and they differ, so each element needs converting. */
    DIFFERENT,
    /** One side names no types, and the other names types its elements cannot be shown to be. */
    UNPROVABLE,
  }

  /**
   * How the arguments of two containers of one kind, element and key alike, relate. A side whose
   * arguments do not resolve to types, which is what a generic class used raw presents, says
   * nothing about its elements: against another such side there is nothing to differ by, against a
   * side that fixes every argument to {@code Object} any element fits, and against a side fixing
   * anything narrower nothing shows the elements fit.
   */
  private ElementMatch elementsMatch(final T a, final T b, final WellKnown kind) {
    final var aArgs = props.typeArgumentsAs(a, kind);
    final var bArgs = props.typeArgumentsAs(b, kind);
    final var aNames = namesTypes(aArgs);
    final var bNames = namesTypes(bArgs);
    if (!aNames && !bNames) return ElementMatch.SAME;
    if (aNames != bNames) {
      final var fixed = aNames ? aArgs : bArgs;
      final var anything = fixed.stream().allMatch(t -> Object.class.getName().equals(props.typeName(t)));
      return anything ? ElementMatch.SAME : ElementMatch.UNPROVABLE;
    }
    if (aArgs.size() != bArgs.size()) return ElementMatch.DIFFERENT;
    for (int i = 0; i < aArgs.size(); i++) {
      if (!props.sameType(aArgs.get(i), bArgs.get(i))) return ElementMatch.DIFFERENT;
    }
    return ElementMatch.SAME;
  }

  /**
   * Whether resolved arguments name types: there is at least one, and none holds a type variable.
   */
  private boolean namesTypes(final List<T> arguments) {
    return !arguments.isEmpty() && arguments.stream().noneMatch(props::mentionsTypeVariable);
  }

  /**
   * Whether a map's key type can be lifted, which needs it to name one type that two sides can be
   * compared on: a lift carries the source's keys into the target unchanged, so the pair is sound
   * only when the two key types are the same type. A plain class qualifies, and so does a
   * parameterized type such as {@code List<String>}. A wildcard key admits keys of types nothing
   * names, and a key holding a type variable stands for whatever that variable is bound to, so
   * neither can be shown to be the same type as anything.
   */
  private boolean decidableKey(final T key) {
    return !props.isWildcard(key) && !props.mentionsTypeVariable(key);
  }

  /**
   * Same-name field matching over the two sides' property names, honoring claims already made by
   * explicit rows. Pure set logic — the strictness gates (what happens to the unmatched leftovers)
   * are the caller's policy; the matches and leftovers themselves are decided here, identically in
   * both worlds. Iteration order follows the target side, matching construction order.
   */
  public static MatchResult matchFields(
    final List<String> sourceNames,
    final List<String> targetNames,
    final Set<String> claimedSource,
    final Set<String> claimedTarget
  ) {
    final var srcNameSet = new LinkedHashSet<>(sourceNames);
    final var matched = new ArrayList<String>();
    final var unmatchedTargets = new ArrayList<String>();
    final var claimedSrcNow = new LinkedHashSet<>(claimedSource);
    for (final var name : targetNames) {
      if (claimedTarget.contains(name)) continue;
      if (srcNameSet.contains(name)) {
        matched.add(name);
        claimedSrcNow.add(name);
      } else {
        unmatchedTargets.add(name);
      }
    }
    final var unmatchedSources = new ArrayList<String>();
    for (final var name : sourceNames) {
      if (!claimedSrcNow.contains(name)) unmatchedSources.add(name);
    }
    return new MatchResult(List.copyOf(matched), List.copyOf(unmatchedTargets), List.copyOf(unmatchedSources));
  }

  /**
   * Outcome of {@link #matchFields}: field names paired by same-name auto-match (in target order),
   * target names with no source counterpart, and source names with no consumer.
   */
  public record MatchResult(List<String> matched, List<String> unmatchedTargets, List<String> unmatchedSources) {}

  /**
   * The allocation table: which class a declared container is rebuilt as, and what its constructor
   * is told about the source.
   *
   * <p>How the chosen constructor is called is not decided here. A list's {@code int} is an element
   * count and a hash container's is a table capacity, which for the same elements is a different
   * number, and {@code WeakHashMap} has no factory to do that arithmetic — so the call belongs with
   * the renderer that makes it.
   *
   * <p>Each entry carries the family it belongs to, because the same name must not answer for a
   * kind it has nothing to do with. Asking the map family about a {@code List} is a question with
   * no good answer, and one refused while a plan is built rather than answered wrongly and cast at
   * the first conversion.
   *
   * <p>Names come from class literals rather than string constants, so a typo cannot make an entry
   * dead and a rename cannot leave it behind.
   */
  private static final Map<String, Entry> BY_DECLARED_NAME = Map.ofEntries(
    list(List.class, ArrayList.class, Allocation.Call.COUNT),
    list(ArrayList.class, ArrayList.class, Allocation.Call.COUNT),
    list(LinkedList.class, LinkedList.class, Allocation.Call.NO_ARG),
    list(Deque.class, ArrayDeque.class, Allocation.Call.COUNT),
    list(Queue.class, ArrayDeque.class, Allocation.Call.COUNT),
    list(Vector.class, Vector.class, Allocation.Call.COUNT),
    list(Stack.class, Stack.class, Allocation.Call.NO_ARG),
    set(Set.class, LinkedHashSet.class, Allocation.Call.TABLE_FACTORY),
    set(LinkedHashSet.class, LinkedHashSet.class, Allocation.Call.TABLE_FACTORY),
    set(HashSet.class, HashSet.class, Allocation.Call.TABLE_FACTORY),
    set(TreeSet.class, TreeSet.class, Allocation.Call.ORDERING),
    set(SortedSet.class, TreeSet.class, Allocation.Call.ORDERING),
    set(NavigableSet.class, TreeSet.class, Allocation.Call.ORDERING),
    set(ConcurrentSkipListSet.class, ConcurrentSkipListSet.class, Allocation.Call.ORDERING),
    map(Map.class, LinkedHashMap.class, Allocation.Call.TABLE_FACTORY),
    map(LinkedHashMap.class, LinkedHashMap.class, Allocation.Call.TABLE_FACTORY),
    map(HashMap.class, HashMap.class, Allocation.Call.TABLE_FACTORY),
    map(TreeMap.class, TreeMap.class, Allocation.Call.ORDERING),
    map(SortedMap.class, TreeMap.class, Allocation.Call.ORDERING),
    map(NavigableMap.class, TreeMap.class, Allocation.Call.ORDERING),
    map(ConcurrentHashMap.class, ConcurrentHashMap.class, Allocation.Call.COUNT),
    map(ConcurrentMap.class, ConcurrentHashMap.class, Allocation.Call.COUNT),
    map(ConcurrentSkipListMap.class, ConcurrentSkipListMap.class, Allocation.Call.ORDERING),
    map(IdentityHashMap.class, IdentityHashMap.class, Allocation.Call.COUNT),
    map(WeakHashMap.class, WeakHashMap.class, Allocation.Call.TABLE_ARITHMETIC),
    map(EnumMap.class, EnumMap.class, Allocation.Call.KEY_CLASS)
  );

  /** The families, computed once: the table's own iteration order is not meaningful. */
  private static final Map<String, ContainerView.Kind> DECLARED_TYPES = BY_DECLARED_NAME.entrySet()
    .stream()
    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().family()));

  /** One row: the family it answers for, and what it answers. */
  private record Entry(ContainerView.Kind family, Allocation allocation) {}

  private static Map.Entry<String, Entry> list(final Class<?> d, final Class<?> i, final Allocation.Call c) {
    return row(ContainerView.Kind.LIST, d, i, c);
  }

  private static Map.Entry<String, Entry> set(final Class<?> d, final Class<?> i, final Allocation.Call c) {
    return row(ContainerView.Kind.SET, d, i, c);
  }

  private static Map.Entry<String, Entry> map(final Class<?> d, final Class<?> i, final Allocation.Call c) {
    return row(ContainerView.Kind.MAP_VALUES, d, i, c);
  }

  private static Map.Entry<String, Entry> row(
    final ContainerView.Kind family,
    final Class<?> declared,
    final Class<?> impl,
    final Allocation.Call call
  ) {
    return Map.entry(declared.getName(), new Entry(family, new Allocation.Build(impl.getName(), call)));
  }

  /**
   * What this declared container is rebuilt as when it is being built as {@code kind}, or null when
   * the table has no answer and the caller's own fallbacks decide.
   *
   * <p>Null is not a refusal. A type the table does not name may still be reachable another way — a
   * public constructor bound at run time, a family default written into source — and answering for
   * those belongs to the side doing the building. A type the table names for a different family is
   * also null here, so a mismatch is refused where the caller refuses rather than answered with a
   * container of the wrong shape.
   */
  public Allocation allocationFor(final T declared, final ContainerView.Kind kind) {
    final var name = props.typeName(props.rawType(declared));
    // A declaration that names no shape is rebuilt as whatever it was paired against. Collection is
    // the union of the two, so the kind the pair settled on is the only thing that says which.
    if (Collection.class.getName().equals(name)) {
      return switch (kind) {
        case SET -> new Allocation.Build(LinkedHashSet.class.getName(), Allocation.Call.TABLE_FACTORY);
        case LIST -> new Allocation.Build(ArrayList.class.getName(), Allocation.Call.COUNT);
        // A COLLECTION view is settled to one of the two before anything asks for an allocation,
        // so answering it here would be answering a question nothing puts.
        default -> null;
      };
    }
    final var entry = BY_DECLARED_NAME.get(name);
    return entry == null || entry.family() != kind ? null : entry.allocation();
  }

  /**
   * Why a pair of same-kind container classes cannot be copied whoever builds it, or null when each
   * side has a class to build. Asked of a concrete side only: one the table refuses outright, which
   * a raw {@code EnumMap} is, since it names no key class to build from. An interface or abstract
   * side with no default goes on to the container lift, which refuses it in the same words on both
   * paths.
   */
  public String copyRefusal(final T srcType, final T tgtType) {
    final var allocation = new ContainerAllocation<T>(props);
    for (final var side : List.of(srcType, tgtType)) {
      if (props.isAbstractType(props.rawType(side))) continue;
      if (
        allocation.implementationFor(side, familyOf(side)) instanceof Allocation.Refuse refuse
      ) return refuse.reason();
    }
    return null;
  }

  /**
   * What a family builds in place of a declaration the table does not name: the answer its root
   * interface gets, so an abstract type standing in for a list is built as a {@code List} is.
   */
  static Allocation.Build familyDefault(final ContainerView.Kind kind) {
    final var root = switch (kind) {
      case LIST -> List.class;
      case SET -> Set.class;
      case MAP_VALUES -> Map.class;
      case OPTIONAL, COLLECTION -> throw new IllegalArgumentException("no family default for " + kind);
    };
    return (Allocation.Build) BY_DECLARED_NAME.get(root.getName()).allocation();
  }

  /**
   * Whether an interface or abstract container type has a default implementation a copy can build
   * in its place: the class the allocation table rebuilds it as in the family it belongs to, a
   * map's for a map, a set's for a set, and a list's for any other collection. Every class the
   * table names implements the declaration it is named for, so an answer here is a class the field
   * can hold.
   */
  public boolean hasDefaultImplementation(final T declared) {
    return allocationFor(declared, familyOf(declared)) instanceof Allocation.Build;
  }

  /**
   * The family a container class is built in when no pair has settled it: a map's for a map, a
   * set's for a set, and a list's for any other collection.
   */
  public ContainerView.Kind familyOf(final T declared) {
    if (props.isSubtypeOf(declared, WellKnown.MAP)) return ContainerView.Kind.MAP_VALUES;
    return props.isSubtypeOf(declared, WellKnown.SET) ? ContainerView.Kind.SET : ContainerView.Kind.LIST;
  }

  /**
   * The declared types the table answers for, with the family each answers in.
   *
   * <p>A side that renders these has to render all of them: an entry nothing renders is not a
   * compile error, it is a container quietly built some other way. Exposing the set is what lets a
   * renderer be checked against the decision rather than against a list kept in step by hand.
   *
   * <p>{@code Collection} is deliberately absent. It has no entry, because what it is rebuilt as
   * depends on the kind its pair settled on rather than on the declaration, so it is answered in
   * {@link #allocationFor} instead.
   */
  public static Map<String, ContainerView.Kind> declaredTypes() {
    return DECLARED_TYPES;
  }
}
