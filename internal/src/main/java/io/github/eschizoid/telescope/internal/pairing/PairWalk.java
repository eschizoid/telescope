package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The type pairs a mapper visits, walked one way for the runtime mapper and the compile-time
 * verifier alike, so a row keyed to a pair the walk never reaches is refused by one rule on both.
 *
 * <p>The walk starts at the mapper's own pair. At a pair of records or beans it follows, in order,
 * each row keyed to the pair that passes a property through unchanged, then each target property no
 * row with accessors on both sides claims and the source has under the same name. The two
 * properties of each are read as {@link PairingRules#pairedTypes} reads them, and {@link
 * PairingRules#decidePair} decides where they lead: a pair of records or beans on to that pair, as
 * its classes whatever type arguments the properties give them; a pair of sealed interfaces on to
 * the pair of each case; an {@code Optional} or a container on to its elements; anything else
 * nowhere. A pair the shared decision refuses leaves the walk open: construction refuses that pair
 * itself, so no row is refused for a pair the walk did not reach, and the refusal named is the
 * property's own. A row with a conversion of its own claims its target property and leads nowhere;
 * a row through a telescope path, and a {@code constant}, {@code compute}, {@code when} or {@code
 * drop} row, claims nothing the walk follows. Each pair is walked once, so a cycle ends where it
 * closes.
 *
 * <p>A row keyed to two sealed interfaces applies to every pair of classes the walk meets that
 * implement them, after the pair's own rows ({@link #inheritsRows}): its accessors are declared on
 * the interfaces, so each case has them. Where two rows claim one property of a pair, the more
 * specific applies ({@link #inherited}): a row of the pair's own over one keyed to interfaces when
 * both convert the same target property or both drop the same source property ({@link
 * #overridden}), and a row keyed to interfaces extending another's on both sides over that other.
 * Two rows keyed to interfaces neither of which extends the other's are refused, as is a case pair
 * on which one of a row's accessors reads no property ({@link #offCase}); either leaves the walk
 * open. Such a row counts as visited where the walk visits the pair of interfaces or a pair it
 * applies to.
 *
 * <p>What only one world can tell is asked of {@link Decisions}: whether a nested pair converts
 * through a bridge of its own, which then stands in for the walk below it, and which case of a
 * sealed interface pairs with which.
 *
 * @param <T> the world's type handle
 */
public final class PairWalk<T> {

  /** What a row does at the pair it is keyed to, as far as which pairs the walk visits. */
  public enum RowKind {
    /** {@code to(src, tgt)}: claims the target property and leads where an unclaimed one does. */
    PASS_THROUGH,
    /** A row with a conversion of its own: claims the target property, and leads nowhere. */
    CONVERTING,
    /** A row through a telescope path, or {@code constant}, {@code compute} or {@code when}. */
    PATH,
    /** {@code drop}, which is never refused for a pair the walk does not visit. */
    DROP,
  }

  /**
   * A row and the pair it is keyed to.
   *
   * @param source the class the row is keyed to on the source side
   * @param target the class the row is keyed to on the target side
   * @param kind what the row does at that pair
   * @param sourceAccessor the source accessor's method name as written, or null for a path
   * @param targetAccessor the target accessor's method name as written, or null for a path
   * @param sourcePathStart for a source given as a path, the method name its first step reads
   *     through, or null where there is none or it is not known
   * @param targetPathStart the same, for a target given as a path
   * @param <T> the world's type handle
   */
  public record KeyedRow<T>(
    T source,
    T target,
    RowKind kind,
    String sourceAccessor,
    String targetAccessor,
    String sourcePathStart,
    String targetPathStart
  ) {
    /** A row with no side given as a path, or whose paths' first steps are not known. */
    public KeyedRow(
      final T source,
      final T target,
      final RowKind kind,
      final String sourceAccessor,
      final String targetAccessor
    ) {
      this(source, target, kind, sourceAccessor, targetAccessor, null, null);
    }
  }

  /** A row keyed to two sealed interfaces that a pair of their cases cannot take, and why. */
  public record RowOffCase<T>(KeyedRow<T> row, String message) {}

  /** A pair of classes the walk meets. */
  public record Pair<T>(T source, T target) {}

  /** What a walk asks of the world it runs in. */
  public interface Decisions<T> {
    /**
     * Whether the pair of {@code source} and {@code target} converts through a bridge of its own,
     * which then stands in for the walk below it. {@code around} holds the pairs being walked
     * around it, the innermost first, and reads them only for the length of the call. Never asked
     * of the mapper's own pair.
     */
    boolean ownBridge(T source, T target, List<Pair<T>> around);

    /**
     * The pairs the cases of the sealed {@code sourceRoot} make with the cases of the sealed {@code
     * targetRoot}, one per case of the source in the order it permits them, or null where this
     * world cannot tell or construction refuses the pairing, which then leaves the walk open.
     */
    List<Pair<T>> casePairs(T sourceRoot, T targetRoot);

    /**
     * Told of each property the walk fills on a pair it walks: from a row with accessors on both
     * sides, other than {@code drop}, and from each same-named property no such row claims.
     */
    default void fills(final T source, final T target, final String sourceProperty, final String targetProperty) {}
  }

  /**
   * What a walk found.
   *
   * @param visited every pair the walk visited, in the order it first met each
   * @param dispatched the pairs of sealed interfaces among them
   * @param open whether the walk met a pair the shared decision refuses, or a pair of sealed
   *     interfaces whose cases this world cannot pair or construction refuses to pair, so that
   *     construction refuses for its own reason or what lies below is unknown, and no row is
   *     refused
   * @param neverVisited the rows keyed to a pair the walk never visits, {@code drop} rows aside, in
   *     the order they were given; empty when {@code open}
   * @param refusal the refusal those rows earn, or null when there are none
   * @param offCase each row keyed to two sealed interfaces that a pair of their cases the walk met
   *     cannot take, in the order met ({@link #offCase})
   * @param <T> the world's type handle
   */
  public record Walk<T>(
    List<Pair<T>> visited,
    List<Pair<T>> dispatched,
    boolean open,
    List<KeyedRow<T>> neverVisited,
    String refusal,
    List<RowOffCase<T>> offCase
  ) {}

  private final PropertySystem<T> props;
  private final PairingRules<T> rules;

  public PairWalk(final PropertySystem<T> props) {
    this.props = props;
    this.rules = new PairingRules<>(props);
  }

  /**
   * Walks the pairs a mapper from {@code source} to {@code target} visits, given the rows keyed to
   * pairs, in the order they were given. {@code forwardOnly} holds for a mapper with no backward
   * direction, which decides which enum pairs convert.
   */
  public Walk<T> walk(
    final T source,
    final T target,
    final List<KeyedRow<T>> rows,
    final boolean forwardOnly,
    final Decisions<T> decisions
  ) {
    final var run = new Run(rows, forwardOnly, decisions);
    final var rootSource = props.rawType(source);
    final var rootTarget = props.rawType(target);
    if (rules.decidePair(rootSource, rootTarget, "", forwardOnly) instanceof PairDecision.DispatchCases) {
      run.sealed(rootSource, rootTarget);
    } else run.pair(rootSource, rootTarget);
    if (run.open) return new Walk<>(
      List.copyOf(run.visited.values()),
      List.copyOf(run.dispatched),
      true,
      List.of(),
      null,
      List.copyOf(run.offCase)
    );
    List<KeyedRow<T>> neverVisited = null;
    Map<Key, List<String>> dead = null;
    for (final var row : rows) {
      final var key = run.key(props.rawType(row.source()), props.rawType(row.target()));
      if (run.visited.containsKey(key) || run.applied.contains(row)) continue;
      if (dead == null) {
        dead = new LinkedHashMap<>();
        neverVisited = new ArrayList<>();
      }
      final var listed = dead.computeIfAbsent(key, __ -> new ArrayList<>());
      if (row.kind() == RowKind.DROP) continue;
      neverVisited.add(row);
      listed.add(PairingMessages.unvisitedRow(row.sourceAccessor(), row.targetAccessor()));
    }
    String refusal = null;
    if (neverVisited != null && !neverVisited.isEmpty()) {
      final var pairs = new ArrayList<String>();
      for (final var entry : dead.entrySet()) {
        if (entry.getValue().isEmpty()) continue;
        pairs.add(PairingMessages.unvisitedPair(entry.getKey().source(), entry.getKey().target(), entry.getValue()));
      }
      refusal = PairingMessages.rowsNeverVisited(nameOf(rootSource), nameOf(rootTarget), pairs);
    }
    return new Walk<>(
      List.copyOf(run.visited.values()),
      List.copyOf(run.dispatched),
      false,
      neverVisited == null ? List.of() : List.copyOf(neverVisited),
      refusal,
      List.of()
    );
  }

  /**
   * Whether a row keyed to {@code rowSource} and {@code rowTarget} applies to the pair of {@code
   * caseSource} and {@code caseTarget}, which are not that pair: both are sealed interfaces, and
   * each class of the pair implements the one on its side. Every pair of classes of a mapper
   * converts through one conversion wherever it is met, so a row applies to the pair wherever the
   * walk meets it.
   */
  public boolean inheritsRows(final T caseSource, final T caseTarget, final T rowSource, final T rowTarget) {
    if (!props.isSealedInterfaceType(rowSource) || !props.isSealedInterfaceType(rowTarget)) return false;
    if (props.sameType(caseSource, rowSource) && props.sameType(caseTarget, rowTarget)) return false;
    return props.isAssignable(caseSource, rowSource) && props.isAssignable(caseTarget, rowTarget);
  }

  /**
   * Whether {@code inherited}, a row keyed to two sealed interfaces, gives way on the pair of
   * {@code caseSource} and {@code caseTarget} to one of {@code own}, the rows keyed to that pair,
   * which are the more specific: one that converts the same target property, or drops the same
   * source property.
   */
  public boolean overridden(
    final KeyedRow<T> inherited,
    final List<KeyedRow<T>> own,
    final T caseSource,
    final T caseTarget
  ) {
    if (own == null) return false;
    for (final var row : own) {
      if (claimed(inherited, row, caseSource, caseTarget) != null) return true;
    }
    return false;
  }

  /**
   * The property {@code a} and {@code b} both claim on the pair of {@code caseSource} and {@code
   * caseTarget}, or null when they claim none in common: the target property both convert, or the
   * source property both drop.
   */
  private String claimed(final KeyedRow<T> a, final KeyedRow<T> b, final T caseSource, final T caseTarget) {
    if (converts(a) && converts(b)) {
      final var written = propertyName(a.targetAccessor(), caseTarget);
      return written.equals(propertyName(b.targetAccessor(), caseTarget)) ? written : null;
    }
    if (a.kind() == RowKind.DROP && b.kind() == RowKind.DROP) {
      final var dropped = propertyName(a.sourceAccessor(), caseSource);
      return dropped.equals(propertyName(b.sourceAccessor(), caseSource)) ? dropped : null;
    }
    return null;
  }

  /**
   * The rows keyed to two sealed interfaces the pair of {@code caseSource} and {@code caseTarget}
   * applies, and those it refuses, out of {@code candidates}, given {@code own}, the rows keyed to
   * the pair. A candidate applies where the pair's classes implement its interfaces ({@link
   * #inheritsRows}), unless a row of the pair's own claims the same property ({@link #overridden})
   * or another candidate does whose interfaces extend its own on both sides, which is the more
   * specific. Two candidates claiming one property of the pair, neither of whose pairs of
   * interfaces extends the other's, are refused, naming both; two keyed to one pair of interfaces
   * are left to the refusal any two rows keyed to one pair earn. A candidate the pair cannot take
   * is refused by name ({@link #offCase}).
   */
  public Inherited<T> inherited(
    final List<KeyedRow<T>> candidates,
    final List<KeyedRow<T>> own,
    final T caseSource,
    final T caseTarget
  ) {
    final var matching = new ArrayList<KeyedRow<T>>();
    for (final var row : candidates) {
      if (!inheritsRows(caseSource, caseTarget, props.rawType(row.source()), props.rawType(row.target()))) continue;
      if (!overridden(row, own, caseSource, caseTarget)) matching.add(row);
    }
    final var applied = new ArrayList<KeyedRow<T>>();
    final var refused = new ArrayList<RowOffCase<T>>();
    for (var i = 0; i < matching.size(); i++) {
      final var row = matching.get(i);
      var outranked = false;
      String clash = null;
      for (var j = 0; j < matching.size() && !outranked; j++) {
        final var other = matching.get(j);
        if (j == i || sameInterfaces(row, other)) continue;
        final var property = claimed(row, other, caseSource, caseTarget);
        if (property == null) continue;
        if (extendsBoth(other, row)) outranked = true;
        else if (j < i && clash == null && !extendsBoth(row, other)) {
          clash = PairingMessages.interfaceRowsClash(
            PairingMessages.unvisitedRow(other.sourceAccessor(), other.targetAccessor()),
            nameOf(other.source()),
            nameOf(other.target()),
            PairingMessages.unvisitedRow(row.sourceAccessor(), row.targetAccessor()),
            nameOf(row.source()),
            nameOf(row.target()),
            nameOf(caseSource),
            nameOf(caseTarget),
            property
          );
        }
      }
      if (outranked) continue;
      if (clash != null) {
        refused.add(new RowOffCase<>(row, clash));
        continue;
      }
      final var offCase = offCase(row, caseSource, caseTarget);
      if (offCase != null) refused.add(new RowOffCase<>(row, offCase));
      else applied.add(row);
    }
    return new Inherited<>(List.copyOf(applied), List.copyOf(refused));
  }

  /** What {@link #inherited} decides for one pair. */
  public record Inherited<T>(List<KeyedRow<T>> applied, List<RowOffCase<T>> refused) {}

  /** Whether {@code a} and {@code b} are keyed to the same pair of interfaces. */
  private boolean sameInterfaces(final KeyedRow<T> a, final KeyedRow<T> b) {
    return (
      props.sameType(props.rawType(a.source()), props.rawType(b.source())) &&
      props.sameType(props.rawType(a.target()), props.rawType(b.target()))
    );
  }

  /**
   * Whether the interfaces {@code narrower} is keyed to extend those {@code wider} is, on both
   * sides.
   */
  private boolean extendsBoth(final KeyedRow<T> narrower, final KeyedRow<T> wider) {
    return (
      props.isAssignable(props.rawType(narrower.source()), props.rawType(wider.source())) &&
      props.isAssignable(props.rawType(narrower.target()), props.rawType(wider.target()))
    );
  }

  /**
   * Whether {@code row} claims its target property: it has accessors on both sides and converts.
   */
  private static boolean converts(final KeyedRow<?> row) {
    return row.kind() == RowKind.PASS_THROUGH || row.kind() == RowKind.CONVERTING;
  }

  /**
   * Why {@code row}, keyed to two sealed interfaces, cannot apply to the pair of their cases {@code
   * caseSource} and {@code caseTarget}, or null when it can: an accessor it reads, or the first
   * step of a path it reads or writes, names no property of the case on that side.
   */
  public String offCase(final KeyedRow<T> row, final T caseSource, final T caseTarget) {
    final var reads = row.sourceAccessor() != null ? row.sourceAccessor() : row.sourcePathStart();
    final var writes = row.targetAccessor() != null ? row.targetAccessor() : row.targetPathStart();
    final var readsAt = reads == null ? null : propertyName(reads, caseSource);
    if (readsAt != null && named(props.properties(caseSource), readsAt) == null) {
      return offCaseMessage(row, caseSource, caseTarget, caseSource, readsAt);
    }
    if (row.kind() == RowKind.DROP || writes == null) return null;
    final var writesAt = propertyName(writes, caseTarget);
    if (named(props.properties(caseTarget), writesAt) == null) {
      return offCaseMessage(row, caseSource, caseTarget, caseTarget, writesAt);
    }
    return null;
  }

  private String offCaseMessage(
    final KeyedRow<T> row,
    final T caseSource,
    final T caseTarget,
    final T lacking,
    final String property
  ) {
    return PairingMessages.interfaceRowOffCase(
      PairingMessages.unvisitedRow(row.sourceAccessor(), row.targetAccessor()),
      nameOf(row.source()),
      nameOf(row.target()),
      nameOf(caseSource),
      nameOf(caseTarget),
      nameOf(lacking),
      property
    );
  }

  /** The property an accessor names on {@code owner}: a record component as written. */
  private String propertyName(final String accessor, final T owner) {
    return props.isRecordType(owner) ? accessor : PropertyNames.property(accessor);
  }

  /** The first of {@code properties} named {@code name}, or null. */
  private static <T> Property<T> named(final List<Property<T>> properties, final String name) {
    for (final var property : properties) if (property.name().equals(name)) return property;
    return null;
  }

  /** A class by its binary name, as a refusal names it. */
  private String nameOf(final T cls) {
    return props.typeName(props.rawType(cls));
  }

  /** A pair of classes as the walk keys it: by binary name on each side. */
  private record Key(String source, String target) {}

  /** One walk's state. */
  private final class Run {

    private final Map<Key, List<KeyedRow<T>>> rowsByPair;
    private final boolean forwardOnly;
    private final Decisions<T> decisions;
    private final Map<Key, Pair<T>> visited = new LinkedHashMap<>();
    private final List<Pair<T>> dispatched = new ArrayList<>();
    private final List<RowOffCase<T>> offCase = new ArrayList<>();
    // The rows keyed to two sealed interfaces some pair the walk visited applies.
    private final Set<KeyedRow<T>> applied = Collections.newSetFromMap(new IdentityHashMap<>());
    // The rows keyed to two sealed interfaces, which each pair of their cases applies.
    private final List<KeyedRow<T>> interfaceRows = new ArrayList<>();
    // The pairs being walked, the outermost first; read innermost first through `inward`.
    private final ArrayList<Pair<T>> around = new ArrayList<>();
    private final List<Pair<T>> inward = around.reversed();
    private boolean open;

    Run(final List<KeyedRow<T>> rows, final boolean forwardOnly, final Decisions<T> decisions) {
      this.forwardOnly = forwardOnly;
      this.decisions = decisions;
      this.rowsByPair = HashMap.newHashMap(rows.size());
      for (final var row : rows) {
        rowsByPair
          .computeIfAbsent(key(props.rawType(row.source()), props.rawType(row.target())), __ -> new ArrayList<>(1))
          .add(row);
        if (props.isSealedInterfaceType(row.source()) && props.isSealedInterfaceType(row.target())) {
          interfaceRows.add(row);
        }
      }
    }

    Key key(final T source, final T target) {
      return new Key(props.typeName(source), props.typeName(target));
    }

    /** Walks a pair of records or beans, by class. */
    void pair(final T source, final T target) {
      final var key = key(source, target);
      if (visited.containsKey(key)) return;
      final var pair = new Pair<>(source, target);
      visited.put(key, pair);
      around.add(pair);
      try {
        final var sourceProperties = props.properties(source);
        final var targetProperties = props.properties(target);
        final var rows = rowsAt(key, source, target);
        Set<String> claimed = Set.of();
        if (rows != null) {
          claimed = new HashSet<>();
          for (final var row : rows) {
            if (row.kind() != RowKind.PASS_THROUGH && row.kind() != RowKind.CONVERTING) continue;
            final var sourceName = propertyName(row.sourceAccessor(), source);
            final var targetName = propertyName(row.targetAccessor(), target);
            claimed.add(targetName);
            decisions.fills(source, target, sourceName, targetName);
            if (row.kind() == RowKind.PASS_THROUGH) {
              property(source, named(sourceProperties, sourceName), target, named(targetProperties, targetName));
            }
          }
        }
        for (final var targetProperty : targetProperties) {
          final var name = targetProperty.name();
          final var sourceProperty = named(sourceProperties, name);
          if (claimed.contains(name) || sourceProperty == null) continue;
          decisions.fills(source, target, name, name);
          property(source, sourceProperty, target, targetProperty);
        }
      } finally {
        around.removeLast();
      }
    }

    /** Walks a pair of sealed interfaces, case by case. */
    void sealed(final T source, final T target) {
      final var key = key(source, target);
      if (visited.containsKey(key)) return;
      final var pair = new Pair<>(source, target);
      visited.put(key, pair);
      dispatched.add(pair);
      around.add(pair);
      try {
        final var cases = decisions.casePairs(source, target);
        if (cases == null) {
          open = true;
          return;
        }
        for (final var each : cases) types(each.source(), each.target(), "");
      } finally {
        around.removeLast();
      }
    }

    private void property(
      final T source,
      final Property<T> sourceProperty,
      final T target,
      final Property<T> targetProperty
    ) {
      if (sourceProperty == null || targetProperty == null) return;
      final var paired = rules.pairedTypes(
        sourceProperty.member(),
        targetProperty.member(),
        sourceProperty.declared(),
        targetProperty.declared(),
        source,
        target
      );
      types(paired.source(), paired.target(), sourceProperty.name());
    }

    /** Follows the shared decision for one pair of types to the pairs it leads to. */
    private void types(final T source, final T target, final String componentName) {
      switch (rules.decidePair(source, target, componentName, forwardOnly)) {
        case PairDecision.RecursePair<T> __ -> {
          final var sourceClass = props.rawType(source);
          final var targetClass = props.rawType(target);
          if (!decisions.ownBridge(sourceClass, targetClass, inward)) pair(sourceClass, targetClass);
        }
        case PairDecision.DispatchCases<T> __ -> sealed(props.rawType(source), props.rawType(target));
        case PairDecision.OptionalToNullable<T> d -> types(d.elementSrc(), d.elementTgt(), componentName + "[*]");
        case PairDecision.NullableToOptional<T> d -> types(d.elementSrc(), d.elementTgt(), componentName + "[*]");
        case PairDecision.LiftContainer<T> d -> types(
          d.src().elementType(),
          d.tgt().elementType(),
          componentName + "[*]"
        );
        case PairDecision.Incompatible<T> __ -> open = true;
        default -> {
        }
      }
    }

    /**
     * The rows the pair applies: its own, then each row keyed to two sealed interfaces it inherits
     * ({@link #inherited}), in the order given, leaving out one the pair refuses, which is recorded
     * and leaves the walk open. Null when there are none.
     */
    private List<KeyedRow<T>> rowsAt(final Key key, final T source, final T target) {
      final var own = rowsByPair.get(key);
      if (interfaceRows.isEmpty()) return own;
      final var inherited = inherited(interfaceRows, own, source, target);
      if (!inherited.refused().isEmpty()) {
        offCase.addAll(inherited.refused());
        open = true;
      }
      if (inherited.applied().isEmpty()) return own;
      final var all = own == null ? new ArrayList<KeyedRow<T>>() : new ArrayList<>(own);
      all.addAll(inherited.applied());
      applied.addAll(inherited.applied());
      return all;
    }
  }
}
