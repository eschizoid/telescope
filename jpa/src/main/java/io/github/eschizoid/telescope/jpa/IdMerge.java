package io.github.eschizoid.telescope.jpa;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.internal.Beans;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Merges a collection of entities by identifier: a converted element whose identifier matches an
 * element the collection holds is written into that element in place, a new one is added, and a
 * held element nothing matches is removed. Clearing and refilling would keep the collection but
 * replace every element, which a provider writes as a DELETE and an INSERT per row, and which it
 * refuses at flush for a new instance carrying the identifier of a managed one.
 *
 * <ul>
 *   <li>An element's identifier is read from the converted element, so whatever the element
 *       conversion writes into it is what is matched. A converted element with no identifier, or
 *       with a primitive identifier at its default, is new.
 *   <li>A non-null identifier that matches nothing is new where the identifier is assigned, and
 *       refused where it is generated, since the provider assigns a new row's generated identifier.
 *       Two converted elements carrying one identifier are refused, and so are two sent elements
 *       carrying different identifiers that a set kept one of because they converted equal.
 *   <li>The elements are matched in the check that runs before the first write, which follows each
 *       matched element into its own collections, so a refusal at any depth is made before anything
 *       is written. A collection the provider has not loaded is loaded there, so its refusals come
 *       before the first write too. Outside its session it cannot be loaded, and core writes the
 *       property through its setter, as without this module.
 *   <li>Matched elements are written first; the collection then drops what nothing matched before
 *       it takes a new element. A list ends in the converted collection's order. A collection that
 *       already holds the merged elements, in that order for a list and each where it looks for it
 *       for any other, is left untouched, so an unchanged collection leaves its owner clean; any
 *       other is cleared and given the merged elements again, held ones directly and new ones as
 *       the write adds them, which also files each element of a set under its current hash.
 * </ul>
 *
 * <p>Anything this does not apply to is written as the standard refill writes it: a map, elements
 * whose conversion cannot write one into another in place, a collection holding or given an element
 * that is no entity with a readable identifier, and a write in which no element matches, where the
 * merge and the refill are the same.
 */
final class IdMerge {

  /** Stands in the index for an identifier a converted element has already taken. */
  private static final Object CLAIMED = new Object();

  /** What a check hands the refill of a write the merge does not apply to. */
  private static final Object STANDARD = new Object();

  /**
   * What Hibernate throws for a lazy collection read outside its session, named rather than linked,
   * so the module needs no provider at compile time.
   */
  private static final String OUTSIDE_SESSION = "org.hibernate.LazyInitializationException";

  private IdMerge() {}

  /**
   * Matches the write's elements before anything is written, refusing a write the merge would
   * refuse, and answers the match for the refill of the same write. A held collection the provider
   * has not loaded is loaded here, which costs no query the refill would not issue reading it, so
   * its elements are matched, and refused, before the first write as every other's are.
   */
  static Object check(final ContainerCopy.Refill refill) {
    if (refill.held() instanceof Collection<?> held && !LoadStates.loaded(held) && !loads(held)) return STANDARD;
    final var plan = plan(refill);
    return plan == null ? STANDARD : plan;
  }

  /**
   * Loads {@code held} by reading it, and answers whether it could be. A lazy collection outside
   * its session cannot, and the refill then writes the property through its setter, as without this
   * module. Anything else reading it throws, such as a database error, reaches the caller.
   */
  private static boolean loads(final Collection<?> held) {
    try {
      held.iterator().hasNext();
      return true;
    } catch (final RuntimeException failed) {
      if (outsideSession(failed)) return false;
      throw failed;
    }
  }

  /**
   * Whether {@code failed} is a provider's refusal to read a lazy collection outside its session.
   */
  private static boolean outsideSession(final RuntimeException failed) {
    for (Class<?> type = failed.getClass(); type != null; type = type.getSuperclass()) if (
      type.getName().equals(OUTSIDE_SESSION)
    ) return true;
    return false;
  }

  /** Writes {@code refill}, merged by identifier where it applies and refilled where not. */
  static void refill(final ContainerCopy.Refill refill) {
    final var prepared = refill.prepared();
    final var plan =
      prepared == STANDARD ? null : prepared instanceof Plan known && known.fits(refill) ? known : plan(refill);
    if (plan == null) refill.standard();
    else write(refill, plan);
  }

  /**
   * The match of a write's converted elements to its held ones: the collection held, how many
   * converted elements there are, the held element each matched by its position among them, how
   * many matched, and the source's elements by the same positions where they line up with the
   * converted ones, or null.
   */
  private record Plan(Collection<?> held, int size, Object[] matched, int matches, List<?> sent) {
    /** Whether this is the match of {@code refill}'s containers. */
    boolean fits(final ContainerCopy.Refill refill) {
      return refill.held() == held && refill.incoming() instanceof Collection<?> incoming && incoming.size() == size;
    }

    /**
     * The source's element the converted element at {@code position} was converted from, or null.
     */
    Object sentAt(final int position) {
      return sent == null ? null : sent.get(position);
    }
  }

  /**
   * Matches the converted elements to the held ones, and checks each match's own write, refusing
   * what the merge refuses. Null where the merge does not apply or nothing matches, so the standard
   * refill is the write.
   */
  private static Plan plan(final ContainerCopy.Refill refill) {
    if (!refill.writesElements()) return null;
    if (
      !(refill.held() instanceof Collection<?> held) || !(refill.incoming() instanceof Collection<?> incoming)
    ) return null;
    if (!anyIdentified(incoming)) return null;
    final var index = heldIndex(held);
    if (index == null) return null;
    final var matched = new Object[incoming.size()];
    var matches = 0;
    var position = 0;
    for (final var element : incoming) {
      final var at = position++;
      if (element == null) continue;
      final var identifier = identifierOf(element);
      if (identifier == Identifiers.NONE) return null;
      final var id = identifier.of(element);
      if (id == null) continue;
      final var found = index.put(id, CLAIMED);
      if (found == CLAIMED) throw refill.refused(twice(id, identifier));
      if (found == null) {
        if (identifier.generated()) throw refill.refused(unknown(id, identifier));
        continue;
      }
      matched[at] = found;
      matches++;
    }
    refuseCollapsed(refill, incoming);
    if (matches == 0) return null;
    final var plan = new Plan(held, matched.length, matched, matches, aligned(refill, incoming));
    position = 0;
    for (final var element : incoming) {
      final var at = position++;
      final var kept = matched[at];
      if (kept != null && kept != element) refill.checkElement(kept, element, plan.sentAt(at));
    }
    return plan;
  }

  /**
   * The held elements by identifier, or null where the merge does not apply because an element is
   * no entity with a readable identifier.
   */
  private static Index heldIndex(final Collection<?> held) {
    final var index = new Index(held.size());
    for (final var element : held) {
      if (element == null) continue;
      final var identifier = identifierOf(element);
      if (identifier == Identifiers.NONE) return null;
      final var id = identifier.of(element);
      if (id != null) index.put(id, element);
    }
    return index;
  }

  /**
   * The source's elements, by the positions of the converted ones, where they line up: a list
   * converted into a list of as many elements, element by element. Null otherwise.
   */
  private static List<?> aligned(final ContainerCopy.Refill refill, final Collection<?> incoming) {
    return refill.sent() instanceof List<?> sent && incoming instanceof List<?> && sent.size() == incoming.size()
      ? sent
      : null;
  }

  /**
   * Refuses a converted set that kept one of two sent elements carrying different identifiers,
   * because the two converted equal: written as it stands, the held element the dropped one names
   * would be removed. Each sent element is converted again to read its identifier, which only a set
   * holding fewer elements than were sent costs.
   */
  private static void refuseCollapsed(final ContainerCopy.Refill refill, final Collection<?> incoming) {
    if (!(incoming instanceof Set<?> set) || !(refill.sent() instanceof Collection<?> sent)) return;
    if (sent.size() <= set.size()) return;
    final var kept = new Index(set.size());
    for (final var element : set) {
      if (element == null) continue;
      final var id = identifierOf(element).of(element);
      if (id != null) kept.put(id, element);
    }
    for (final var element : sent) {
      if (element == null) continue;
      final var converted = refill.convertSent(element);
      if (converted == null) continue;
      final var identifier = identifierOf(converted);
      final var id = identifier.of(converted);
      if (id == null || kept.get(id) != null) continue;
      Object survivor = null;
      for (final var held : set) if (converted.equals(held)) survivor = held;
      throw refill.refused(collapsed(id, survivor == null ? null : identifierOf(survivor).of(survivor), identifier));
    }
  }

  /** Writes the merge {@code plan} matched. */
  private static void write(final ContainerCopy.Refill refill, final Plan plan) {
    final var held = plan.held();
    final var incoming = (Collection<?>) refill.incoming();
    final var matched = plan.matched();
    var position = 0;
    for (final var element : incoming) {
      final var at = position++;
      final var kept = matched[at];
      if (kept != null && kept != element) refill.writeElement(kept, element, plan.sentAt(at));
    }
    if (plan.matches() == held.size() && plan.matches() == incoming.size() && holdsAlready(held, matched)) return;
    held.clear();
    position = 0;
    for (final var element : incoming) {
      final var kept = matched[position++];
      if (kept != null) refill.keep(kept);
      else refill.add(element);
    }
    if (held instanceof Set && !refill.throughAdder() && held.size() < incoming.size()) throw refill.refused(
      becameEqual()
    );
  }

  /** Whether any converted element is an entity carrying an identifier. */
  private static boolean anyIdentified(final Collection<?> incoming) {
    for (final var element : incoming) {
      if (element == null) continue;
      final var identifier = identifierOf(element);
      if (identifier != Identifiers.NONE && identifier.of(element) != null) return true;
    }
    return false;
  }

  private static Identifiers.Identifier identifierOf(final Object element) {
    return Identifiers.of(Beans.persistentClassOf(element));
  }

  /**
   * Whether {@code held}, every element of which a converted one matched, holds the merged elements
   * where it would put them: a list, or any collection but a set, in the converted collection's
   * order, and a set each element where it looks for it, so none sits under a hash its write
   * changed.
   */
  private static boolean holdsAlready(final Collection<?> held, final Object[] matched) {
    if (held instanceof Set<?> set) {
      for (final var element : matched) if (!set.contains(element)) return false;
      return true;
    }
    final Iterator<?> elements = held.iterator();
    for (final var element : matched) if (!elements.hasNext() || elements.next() != element) return false;
    return true;
  }

  /**
   * Identifiers to the elements carrying them, open-addressed over two arrays, so matching
   * allocates no entry per element. Sized for the elements expected at half load, and doubled past
   * three quarters, which only identifiers sent for new elements reach.
   */
  private static final class Index {

    private Object[] keys;
    private Object[] values;
    private int size;

    Index(final int expected) {
      final var capacity = Integer.highestOneBit(Math.max(2, expected * 2) - 1) << 1;
      keys = new Object[capacity];
      values = new Object[capacity];
    }

    /** What is filed under {@code key}, or null. */
    Object get(final Object key) {
      final var mask = keys.length - 1;
      for (var slot = spread(key.hashCode()) & mask; keys[slot] != null; slot = (slot + 1) & mask) if (
        keys[slot].equals(key)
      ) return values[slot];
      return null;
    }

    /** Files {@code value} under {@code key}, answering what was filed there before, or null. */
    Object put(final Object key, final Object value) {
      final var mask = keys.length - 1;
      for (var slot = spread(key.hashCode()) & mask; ; slot = (slot + 1) & mask) {
        if (keys[slot] == null) {
          keys[slot] = key;
          values[slot] = value;
          if (++size * 4 > keys.length * 3) grow();
          return null;
        }
        if (keys[slot].equals(key)) {
          final var previous = values[slot];
          values[slot] = value;
          return previous;
        }
      }
    }

    private void grow() {
      final var oldKeys = keys;
      final var oldValues = values;
      keys = new Object[oldKeys.length * 2];
      values = new Object[oldKeys.length * 2];
      size = 0;
      for (var i = 0; i < oldKeys.length; i++) if (oldKeys[i] != null) put(oldKeys[i], oldValues[i]);
    }

    private static int spread(final int hash) {
      return hash ^ (hash >>> 16);
    }
  }

  private static String twice(final Object id, final Identifiers.Identifier identifier) {
    return (
      "Two converted elements carry the identifier " +
      id +
      " of " +
      identifier.entity() +
      ", and a held element can take the values of only one. Send each element once."
    );
  }

  private static String unknown(final Object id, final Identifiers.Identifier identifier) {
    return (
      "A converted element carries the identifier " +
      id +
      ", which no element the collection holds has, and the identifier of " +
      identifier.entity() +
      " is generated, so a new element cannot arrive with one. Send a new element without an" +
      " identifier, or the identifier of an element the collection holds."
    );
  }

  private static String collapsed(
    final Object dropped,
    final Object survivor,
    final Identifiers.Identifier identifier
  ) {
    return (
      "The sent elements carrying the identifiers " +
      dropped +
      " and " +
      survivor +
      " converted into equal values of " +
      identifier.entity() +
      ", and the set keeps one of them, so the element carrying " +
      dropped +
      " would be removed. Give " +
      identifier.entity() +
      " an equals two different rows never share, such as one over its identifier."
    );
  }

  private static String becameEqual() {
    return (
      "Written in place, two of its elements became equal, and the set keeps one of them. Give the" +
      " element class an equals two different rows never share, such as one over its identifier."
    );
  }
}
