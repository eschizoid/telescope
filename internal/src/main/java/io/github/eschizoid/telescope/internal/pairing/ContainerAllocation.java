package io.github.eschizoid.telescope.internal.pairing;

/**
 * What a declared container is rebuilt as, and whether the code rebuilding it can build that class.
 * One decision for both worlds: the runtime turns an answer into an allocator, the processor into
 * the text of a {@code new} expression, and either reports a refusal in the words it carries.
 *
 * <p>Three questions are answered here and nowhere else. Which class to build: the class the shared
 * table names for the declaration, the family default standing in for an interface or abstract type
 * the table does not name, or the declared class itself. How to size it: the {@link
 * Allocation.Call} that class is built through. And whether it can be built from a given package: a
 * class built as itself is built through its no-argument constructor, which reaches a rebuild that
 * is public, or package-private or protected and declared in the package the rebuild is generated
 * into.
 *
 * <p>A pair whose elements pass through unchanged can also be built by copy constructor, which
 * {@link #copiesInPlace} decides. A container reached through a static {@code builder()} is not
 * answered here: both worlds try one only where this refuses, so a refusal is final once neither
 * finds a builder.
 *
 * @param <T> the world's type handle
 */
public final class ContainerAllocation<T> {

  private final PropertySystem<T> props;
  private final PairingRules<T> rules;

  public ContainerAllocation(final PropertySystem<T> props) {
    this.props = props;
    this.rules = new PairingRules<>(props);
  }

  /**
   * The class a container declared as {@code declared}, built as {@code kind}, is rebuilt as,
   * whoever rebuilds it. A refusal here is one no package can get past: the table refuses the
   * declaration, or nothing a rebuild can build is an instance of it.
   *
   * @param kind the family the pair settled on: a list, a set or a map
   */
  public Allocation implementationFor(final T declared, final ContainerView.Kind kind) {
    final var table = rules.allocationFor(declared, kind);
    if (table != null) return keyed(table, declared);
    final var raw = props.rawType(declared);
    if (props.isAbstractType(raw)) {
      final var fallback = PairingRules.familyDefault(kind);
      return props.isImplementedBy(raw, fallback.implName())
        ? fallback
        : new Allocation.Refuse(PairingMessages.noDefaultImplementation(props.sourceName(raw), fallback.implName()));
    }
    return new Allocation.Build(props.sourceName(raw), Allocation.Call.NO_ARG);
  }

  /**
   * The family a container class is built in when no pair has settled it, which is what a copy of a
   * container whose declared type is the same on both sides asks {@link #implementationFor} in.
   */
  public ContainerView.Kind familyOf(final T declared) {
    return rules.familyOf(declared);
  }

  /**
   * The same answer for a rebuild generated into {@code fromPackage}, which also has to be able to
   * call the constructor. A null {@code fromPackage} is a rebuild that reaches every package, which
   * is the runtime's: it binds constructors through a lookup with private access to the class.
   *
   * <p>A private constructor is refused even there. Generated code can never call one, and calling
   * one at run time would accept a pairing the generated path cannot, for no gain the author asked
   * for: a private constructor says the class is not to be built from outside.
   */
  public Allocation allocate(final T declared, final ContainerView.Kind kind, final String fromPackage) {
    final var implementation = implementationFor(declared, kind);
    if (rules.allocationFor(declared, kind) != null) return implementation;
    if (!(implementation instanceof Allocation.Build build)) return implementation;
    final var raw = props.rawType(declared);
    if (props.isAbstractType(raw)) return build;
    final var refused = new Allocation.Refuse(PairingMessages.noReachableConstructor(props.sourceName(raw)));
    return switch (props.noArgConstructorAccess(raw)) {
      case PUBLIC -> build;
      case PACKAGE -> fromPackage == null || fromPackage.equals(props.packageName(raw)) ? build : refused;
      case PRIVATE, NONE -> refused;
    };
  }

  /**
   * Whether a container declared as {@code declared} can be built by handing a container declared
   * as {@code from} to a public constructor of the class these rules build it as, which a rebuild
   * whose elements pass through unchanged may do in place of filling an allocation. A pair is
   * copied that way only where each side can be built from the other.
   *
   * <p>For a class these rules can allocate, the copy is another way of building what they already
   * build, so it needs only the constructor. A container built from its key class is left out: its
   * copy constructor learns the key class from the map it is handed, and refuses an empty one that
   * is not of its own class.
   *
   * <p>For a class they cannot allocate, the copy is the one way left, which is how a class with no
   * no-argument constructor, only one taking the container it copies, is built on both paths. Only
   * a class built as itself qualifies, since a family default always has a no-argument constructor,
   * and not one that keeps an order: which of its constructors a copy reaches is fixed by the
   * declared type of what it is handed, so a source holding a comparator can be copied through an
   * overload that drops it.
   *
   * <p>A container reached through its builder is never copied. Each world asks for a builder
   * before it asks this.
   */
  public boolean copiesInPlace(
    final T declared,
    final ContainerView.Kind kind,
    final T from,
    final String fromPackage
  ) {
    final var raw = props.rawType(declared);
    final var asItself = rules.allocationFor(declared, kind) == null && !props.isAbstractType(raw);
    if (allocate(declared, kind, fromPackage) instanceof Allocation.Build build) {
      if (build.call() == Allocation.Call.KEY_CLASS) return false;
      final var impl = asItself ? raw : props.typeNamed(build.implName());
      return impl != null && props.hasPublicConstructorAccepting(impl, from);
    }
    if (!asItself) return false;
    if (!(rules.orderingFor(declared, raw, kind, true) instanceof Ordering.None<T>)) return false;
    return props.hasPublicConstructorAccepting(raw, from);
  }

  /**
   * A table answer as it applies to this declaration. A container built from its key class needs
   * the declaration to name one, which a declaration used raw does not.
   */
  private Allocation keyed(final Allocation table, final T declared) {
    if (!(table instanceof Allocation.Build build) || build.call() != Allocation.Call.KEY_CLASS) return table;
    // A map whose key type the rules can name has a view; one used raw, or keyed by a wildcard or a
    // type variable, has none, and names no class to build from.
    return rules.containerViewOf(declared) != null
      ? table
      : new Allocation.Refuse(PairingMessages.noKeyClass(props.sourceName(props.rawType(declared))));
  }
}
