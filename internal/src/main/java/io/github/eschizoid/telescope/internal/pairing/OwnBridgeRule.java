package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a nested pair converts through a bridge of its own or is walked, decided once for the
 * runtime mapper and the compile-time verifier alike.
 *
 * <p>A nested pair converts through its own bridge, so that bridge's transforms, leniency, renames
 * and write strategy decide the pair's properties, unless the bridge would pass over something the
 * mapper says:
 *
 * <ul>
 *   <li>a setting that reaches every pair, or a row keyed to the pair, or one keyed to two sealed
 *       interfaces its classes implement ({@link PairWalk#inheritsRows}), or a write hint naming
 *       one of its classes ({@link #configuredReason});
 *   <li>the pair met again inside itself ({@link #settingReason});
 *   <li>a row keyed to a pair its classes reach, a {@code cases} row pairing two classes they
 *       reach, a write hint naming a class they reach, or, for a bridge that does not take the
 *       active path, a pair being walked around it that they reach ({@link #reachReason}).
 * </ul>
 *
 * <p>What a pair's classes reach is read through the properties the bridge walks ({@link
 * #reachedFrom}): a property it converts by a conversion of its own, drops, or fills without
 * reading the source leads nowhere the walk would go.
 *
 * <p>One rule serves one mapper, whose settings it is given, and keeps what it has read.
 *
 * @param <T> the world's type handle
 */
public final class OwnBridgeRule<T> {

  /**
   * What a world knows of a pair's own bridge.
   *
   * @param listsUnwalked whether the bridge names the properties it does not walk; one that does
   *     not is read as walking every property, and no sealed interface is read through to its cases
   * @param unwalkedSource the properties of the source the bridge does not walk
   * @param unwalkedTarget the properties of the target the bridge does not walk
   * @param takesActivePath whether the bridge takes the path of objects being converted, which cuts
   *     a cycle through it at the reference the walk would
   */
  public record Bridge(
    boolean listsUnwalked,
    Set<String> unwalkedSource,
    Set<String> unwalkedTarget,
    boolean takesActivePath
  ) {}

  private final PropertySystem<T> props;
  private final PairingRules<T> rules;
  private final PairWalk<T> walk;
  private final String everyPair;
  private final Collection<? extends Paired<T>> keyed;
  private final Collection<? extends Paired<T>> caseRows;
  private final List<T> hinted;
  private final Map<Reach, Set<String>> reached = new HashMap<>();
  // Object, read once: the upper bound a wildcard that writes none has.
  private T object;

  /**
   * @param everyPair why every pair is walked, for a mapper whose settings reach every pair, or
   *     null
   * @param keyed the pairs the mapper's rows are keyed to, in the order their first rows were given
   * @param caseRows the pairs the mapper's {@code cases} rows name, in the order given
   * @param hinted the classes the mapper's write hints name
   */
  public OwnBridgeRule(
    final PropertySystem<T> props,
    final PairingRules<T> rules,
    final PairWalk<T> walk,
    final String everyPair,
    final Collection<? extends Paired<T>> keyed,
    final Collection<? extends Paired<T>> caseRows,
    final List<T> hinted
  ) {
    this.props = props;
    this.rules = rules;
    this.walk = walk;
    this.everyPair = everyPair;
    this.keyed = keyed;
    this.caseRows = caseRows;
    this.hinted = hinted;
  }

  /**
   * Why a bridge of its own does not convert the pair of {@code source} and {@code target}, read
   * from what the mapper is given about the pair wherever it is met, or null.
   */
  public String configuredReason(final T source, final T target) {
    if (everyPair != null) return everyPair;
    // Each loop is entered only over a non-empty collection: most mappers are given no rows and
    // no hints, and every pair of every mapper asks.
    if (!keyed.isEmpty()) {
      for (final var pair : keyed) {
        if (same(pair.source(), source) && same(pair.target(), target)) return "a row is keyed to the pair";
      }
      for (final var pair : keyed) {
        if (walk.inheritsRows(source, target, pair.source(), pair.target())) {
          return "a row keyed to two sealed interfaces it implements applies to the pair";
        }
      }
    }
    if (hinted.isEmpty()) return null;
    if (contains(hinted, source)) return "a writeBean hint names " + props.simpleName(source);
    if (contains(hinted, target)) return "a writeBean hint names " + props.simpleName(target);
    return null;
  }

  /**
   * As {@link #configuredReason}, and the pair met again inside itself: {@code around} holds the
   * pairs being walked around it.
   */
  public String settingReason(final T source, final T target, final Collection<? extends Paired<T>> around) {
    final var configured = configuredReason(source, target);
    if (configured != null) return configured;
    // A pair met again inside itself, such as the mapper's own pair, keeps the walk it is in.
    if (around.isEmpty()) return null;
    for (final var pair : around) {
      if (same(pair.source(), source) && same(pair.target(), target)) return "the pair is met again inside itself";
    }
    return null;
  }

  /**
   * Why {@code bridge}, the pair's own, does not convert the pair of {@code source} and {@code
   * target}, read from the classes it reaches through the properties the bridge walks, or null when
   * it converts it. {@code around} holds the pairs being walked around it, the innermost first.
   */
  public String reachReason(
    final T source,
    final T target,
    final Collection<? extends Paired<T>> around,
    final Bridge bridge
  ) {
    final var listed = bridge.listsUnwalked();
    final var fromSource = reachedFrom(source, listed ? bridge.unwalkedSource() : Set.of(), listed);
    final var fromTarget = reachedFrom(target, listed ? bridge.unwalkedTarget() : Set.of(), listed);
    for (final var configured : keyed) {
      if (fromSource.contains(nameOf(configured.source())) && fromTarget.contains(nameOf(configured.target()))) {
        return (
          "a row is keyed to (" +
          props.simpleName(configured.source()) +
          ", " +
          props.simpleName(configured.target()) +
          "), a pair it reaches"
        );
      }
    }
    for (final var row : caseRows) {
      if (fromSource.contains(nameOf(row.source())) && fromTarget.contains(nameOf(row.target()))) {
        return (
          "a cases row pairs " +
          props.simpleName(row.source()) +
          " with " +
          props.simpleName(row.target()) +
          ", a pair it reaches"
        );
      }
    }
    for (final var hint : hinted) {
      if (fromSource.contains(nameOf(hint)) || fromTarget.contains(nameOf(hint))) {
        return "a writeBean hint names " + props.simpleName(hint) + ", a class it reaches";
      }
    }
    // A bridge taking the active path cuts a cycle through it at the reference the walk would.
    if (bridge.takesActivePath()) return null;
    for (final var ancestor : around) {
      if (fromSource.contains(nameOf(ancestor.source())) && fromTarget.contains(nameOf(ancestor.target()))) {
        return (
          "it reaches (" +
          props.simpleName(ancestor.source()) +
          ", " +
          props.simpleName(ancestor.target()) +
          "), a pair being converted around it"
        );
      }
    }
    return null;
  }

  /**
   * Every class a value of {@code start} can hold at any depth, by binary name, leaving out what it
   * holds in the properties named in {@code skipped}: the classes and type arguments of its
   * properties' types, and the type arguments its class passes to the classes it extends, which is
   * where a container class of its own names its elements. Read through each record or bean on the
   * way. {@code skipped} applies to {@code start}'s own properties only.
   *
   * <p>With {@code throughCases}, a sealed interface a value holds is read through to its cases,
   * and the sealed interface a case implements is not counted as one it holds. That is asked only
   * for a bridge that lists the properties it does not walk; for any other bridge, what lies beyond
   * a sealed interface cannot be told from what the bridge looks at, so it is not read.
   */
  public Set<String> reachedFrom(final T start, final Set<String> skipped, final boolean throughCases) {
    final var key = new Reach(nameOf(start), skipped, throughCases);
    final var known = reached.get(key);
    if (known != null) return known;
    final var seen = new HashSet<String>();
    final var pending = new ArrayDeque<T>();
    pending.add(start);
    while (!pending.isEmpty()) {
      final var cls = pending.poll();
      final var mentioned = new ArrayList<T>();
      final var visited = new HashSet<T>();
      for (final var supertype : props.supertypes(cls)) classesIn(supertype, mentioned, visited);
      if (throughCases) {
        // A case is a value of the sealed interface it belongs to, not a holder of one, so that
        // interface is no class the case reaches.
        mentioned.removeIf(type -> props.isSealedInterfaceType(type) && props.isAssignable(cls, type));
      }
      if (props.isInterfaceType(cls)) {
        // A sealed interface a value holds holds one of its cases.
        if (props.isSealedInterfaceType(cls)) mentioned.addAll(props.permittedSubclasses(cls));
      } else {
        final var own = same(cls, start);
        for (final var property : props.properties(cls)) {
          if (own && skipped.contains(property.name())) continue;
          // Both types the walk can read a property as: as a member of this class, which resolves
          // a type variable a bean inherits, and as declared.
          final var paired = rules.pairedTypes(
            property.member(),
            property.member(),
            property.declared(),
            property.declared(),
            cls,
            cls
          );
          classesIn(paired.source(), mentioned, visited);
          classesIn(property.declared(), mentioned, visited);
        }
      }
      for (final var next : mentioned) if (seen.add(nameOf(next)) && readable(next, throughCases)) pending.add(next);
    }
    reached.put(key, seen);
    return seen;
  }

  /** A class whose reach is asked for, the properties of its own left out, and how far it reads. */
  private record Reach(String start, Set<String> skipped, boolean throughCases) {}

  /**
   * A record or bean whose properties the walk reads, or, {@code throughCases}, a sealed interface
   * whose cases it reads; no JDK class is either.
   */
  private boolean readable(final T cls, final boolean throughCases) {
    return (
      (rules.reflectable(cls) || (throughCases && props.isSealedInterfaceType(cls))) && !nameOf(cls).startsWith("java.")
    );
  }

  /**
   * The classes {@code type} names, at any depth of its arguments and bounds: a class, or an array
   * class's component; a parameterized type's class and each argument; a generic array's component;
   * a wildcard's bounds, an unwritten upper bound read as {@code Object}; a type variable's bounds.
   * {@code visited} stops a type variable bounded by itself, such as {@code T extends
   * Comparable<T>}.
   */
  private void classesIn(final T type, final List<T> out, final Set<T> visited) {
    if (type == null || !visited.add(type)) return;
    if (props.isClassType(type)) {
      out.add(props.isArrayType(type) ? props.componentType(type) : type);
    } else if (props.isWildcard(type)) {
      final var upper = props.upperBound(type);
      if (upper == null && object == null) object = props.typeNamed("java.lang.Object");
      classesIn(upper == null ? object : upper, out, visited);
      classesIn(props.lowerBound(type), out, visited);
    } else if (props.isTypeVariable(type)) {
      for (final var bound : props.upperBounds(type)) classesIn(bound, out, visited);
    } else if (!props.typeArguments(type).isEmpty()) {
      classesIn(props.rawType(type), out, visited);
      for (final var argument : props.typeArguments(type)) classesIn(argument, out, visited);
    } else {
      classesIn(props.componentType(type), out, visited);
    }
  }

  private String nameOf(final T cls) {
    return props.typeName(props.rawType(cls));
  }

  private boolean same(final T a, final T b) {
    return nameOf(a).equals(nameOf(b));
  }

  private boolean contains(final List<T> classes, final T cls) {
    for (final var each : classes) if (same(each, cls)) return true;
    return false;
  }
}
