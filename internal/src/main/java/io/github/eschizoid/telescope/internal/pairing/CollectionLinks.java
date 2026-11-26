package io.github.eschizoid.telescope.internal.pairing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How the elements a write puts into a collection property, or takes out of it, are tied to the
 * owner that holds it: through the owner's adder and remover, or through the back-reference each
 * element holds to its owner. One decision for both worlds: the runtime binds what it answers to
 * accessors, the processor to the text of calls, and both report it in the same words.
 *
 * <p>Every choice is made from what the classes declare. An adder is a public instance method whose
 * name starts with {@code add}, taking one parameter and returning nothing or the owner; a remover
 * is the same with {@code remove}, and may also return a {@code boolean}. The parameter is the
 * collection's element class, erased; a wider parameter other than {@code Object} counts only on a
 * method named for the collection ({@code addLine(BaseLine)} for {@code lines}), since a method
 * taking an interface the element happens to implement may fill another collection. The prefix
 * keeps a setter, or a method that merely takes an element, from being taken for either. Among
 * several such methods the one whose parameter is the most specific is taken, and among several
 * equally specific ones the one named for the collection. Where the name settles nothing either,
 * none of them is used: the collection is written as one with no adder or remover, with a warning
 * naming them, and a write that has no other way in, a collection with no setter, is refused with
 * them named. A back-reference is never read off a property's type: it is the property a {@code
 * backReference} row names, or else the one {@code @OneToMany(mappedBy)} names, and otherwise there
 * is none for a mapper to set.
 *
 * <p>The facts are the world's to answer: the methods a class has, what each takes and returns, and
 * which class can be passed as which. The decision made from them is this class's alone.
 */
public final class CollectionLinks {

  private CollectionLinks() {}

  /** What a method of one parameter returns, as far as an adder or a remover is concerned. */
  public enum Returns {
    /** {@code void}. */
    VOID,
    /** {@code boolean}, which a remover may return. */
    BOOLEAN,
    /** A class the owner is, or a superclass or interface of it other than {@code Object}. */
    OWNER,
    /** Anything else, which neither an adder nor a remover returns. */
    OTHER,
  }

  /**
   * What one world answers about classes for the choice: whether a value of one class can be passed
   * as a parameter of another, and the simple name a refusal names a class by.
   *
   * @param <T> the world's class handle
   */
  public interface Classes<T> {
    /** Whether a value of {@code from} can be passed as a parameter declared as {@code to}. */
    boolean assignable(T from, T to);

    /** Whether {@code type} is {@code Object}, which any element can be passed as. */
    boolean isObject(T type);

    /** The simple name of {@code type}. */
    String simpleName(T type);
  }

  /**
   * A public instance method of one parameter an owner offers, as one world sees it.
   *
   * @param handle the world's method handle
   * @param name the method's name
   * @param parameter the class its parameter erases to; never a primitive
   * @param returns what it returns
   * @param <M> the world's method handle
   * @param <T> the world's class handle
   */
  public record Method<M, T>(M handle, String name, T parameter, Returns returns) {}

  /**
   * A collection property of an owner and its element class, erased, or null where it names none
   * narrower than {@code Object}, and whether the owner has a setter for it, the way in a write
   * takes where no adder is used.
   *
   * @param <T> the world's class handle
   */
  public record Collection<T>(String name, T element, boolean settable) {}

  /**
   * The adder and the remover of one collection property, each null where the owner has none or its
   * methods leave the choice undecided, and what an undecided choice means for a write.
   *
   * @param refusal why a write into the collection is refused, or null: the adder is undecided and
   *     the owner has no setter for the collection either
   * @param warnings what a mapper warns of when it is built, one line per undecided kind, where the
   *     write is not refused
   * @param unused what {@code explain()} adds to the collection's line, naming the methods left
   *     unused, or null
   * @param <M> the world's method handle
   */
  public record Members<M>(M adder, M remover, String refusal, List<String> warnings, String unused) {
    /** No adder and no remover, and nothing undecided. */
    public static <M> Members<M> empty() {
      return none();
    }

    private static final Members<?> NONE = new Members<>(null, null, null, List.of(), null);

    @SuppressWarnings("unchecked")
    static <M> Members<M> none() {
      return (Members<M>) NONE;
    }
  }

  /**
   * The adder and remover of each of {@code collections}, by property name, among {@code methods},
   * the public instance methods of one parameter that {@code owner} offers.
   *
   * <p>For each kind, the candidates for a collection are the methods of that kind taking its
   * element class, and those named for it taking a class the element can be passed as. A candidate
   * named for another collection of the owner that can take that collection's element is left to
   * it. Of the rest, those whose parameter no other one's is more specific than are kept: one is
   * taken; of several, the one named for the collection is taken, and where none is named for it,
   * none is used. An undecided adder of a collection the owner has no setter for refuses the write,
   * naming the candidates; any other undecided kind is warned of, naming them.
   *
   * @param owner the simple name of the owner class, for the refusal
   * @param collections the owner's collection properties
   * @param methods the owner's public instance methods of one parameter
   * @param classes the world's answers about classes
   * @param <M> the world's method handle
   * @param <T> the world's class handle
   * @return by property name, every collection's members
   */
  public static <M, T> Map<String, Members<M>> members(
    final String owner,
    final List<Collection<T>> collections,
    final List<Method<M, T>> methods,
    final Classes<T> classes
  ) {
    final var found = new LinkedHashMap<String, Members<M>>();
    for (final var collection : collections) {
      if (collection.element() == null) {
        found.put(collection.name(), Members.none());
        continue;
      }
      final var adder = member(collection, collections, methods, classes, Kind.ADD);
      final var remover = member(collection, collections, methods, classes, Kind.REMOVE);
      final var element = classes.simpleName(collection.element());
      final var property = collection.name();
      String refusal = null;
      final var warnings = new ArrayList<String>(2);
      final var unused = new ArrayList<String>(2);
      for (final var kind : List.of(Kind.ADD, Kind.REMOVE)) {
        final var choice = kind == Kind.ADD ? adder : remover;
        if (choice.candidates().isEmpty()) continue;
        final var named = namesFor(kind.prefix, property).getFirst();
        if (kind == Kind.ADD && !collection.settable()) {
          refusal = PairingMessages.collectionMemberUndecided(
            owner,
            property,
            element,
            kind.noun,
            choice.candidates(),
            named
          );
        }
        warnings.add(
          PairingMessages.collectionMemberUnused(owner, property, element, kind.noun, choice.candidates(), named)
        );
        unused.add(PairingMessages.collectionMembersLeftUnused(property, choice.candidates()));
      }
      found.put(
        property,
        new Members<>(
          adder.chosen(),
          remover.chosen(),
          refusal,
          refusal == null ? List.copyOf(warnings) : List.of(),
          unused.isEmpty() ? null : String.join("; ", unused)
        )
      );
    }
    return found;
  }

  /** The two kinds of method a collection is tied to its owner through. */
  private enum Kind {
    ADD("add", "adder"),
    REMOVE("remove", "remover");

    private final String prefix;
    private final String noun;

    Kind(final String prefix, final String noun) {
      this.prefix = prefix;
      this.noun = noun;
    }

    boolean returnsAllowed(final Returns returns) {
      return returns == Returns.VOID || returns == Returns.OWNER || (this == REMOVE && returns == Returns.BOOLEAN);
    }

    boolean shapes(final Method<?, ?> method) {
      return (
        method.name().startsWith(prefix) && method.name().length() > prefix.length() && returnsAllowed(method.returns())
      );
    }
  }

  /**
   * One kind's choice for one collection: the method taken, or null, and the candidates, sorted by
   * signature, the name could not choose between, empty where it did not have to.
   */
  private record Choice<M>(M chosen, List<String> candidates) {}

  private static <M, T> Choice<M> member(
    final Collection<T> collection,
    final List<Collection<T>> collections,
    final List<Method<M, T>> methods,
    final Classes<T> classes,
    final Kind kind
  ) {
    final var element = collection.element();
    final var own = namesFor(kind.prefix, collection.name());
    final var candidates = new ArrayList<Method<M, T>>();
    for (final var method : methods) {
      // A parameter of Object takes any element, so its type tells nothing about the collection.
      if (!kind.shapes(method) || classes.isObject(method.parameter())) continue;
      if (!classes.assignable(element, method.parameter())) continue;
      if (!classes.assignable(method.parameter(), element) && !own.contains(method.name())) continue;
      if (claimedElsewhere(method, collection, collections, classes, kind)) continue;
      candidates.add(method);
    }
    final var specific = new ArrayList<Method<M, T>>();
    for (final var candidate : candidates) {
      var wider = false;
      for (final var other : candidates) {
        if (moreSpecific(other.parameter(), candidate.parameter(), classes)) wider = true;
      }
      if (!wider) specific.add(candidate);
    }
    if (specific.isEmpty()) return new Choice<>(null, List.of());
    if (specific.size() == 1) return new Choice<>(specific.getFirst().handle(), List.of());
    for (final var candidate : specific) {
      if (own.contains(candidate.name())) return new Choice<>(candidate.handle(), List.of());
    }
    final var named = new ArrayList<String>(specific.size());
    for (final var candidate : specific) {
      named.add(candidate.name() + "(" + classes.simpleName(candidate.parameter()) + ")");
    }
    // Each world lists a class's methods in an order of its own, so the words name them sorted.
    named.sort(null);
    return new Choice<>(null, List.copyOf(named));
  }

  /**
   * Whether {@code method} is named for another collection of the owner that can be passed to it,
   * which leaves it to that collection.
   */
  private static <T> boolean claimedElsewhere(
    final Method<?, T> method,
    final Collection<T> collection,
    final List<Collection<T>> collections,
    final Classes<T> classes,
    final Kind kind
  ) {
    for (final var other : collections) {
      if (other == collection || other.element() == null) continue;
      if (!classes.assignable(other.element(), method.parameter())) continue;
      if (namesFor(kind.prefix, other.name()).contains(method.name())) return true;
    }
    return false;
  }

  /**
   * Whether {@code a} is strictly more specific than {@code b}: passable as it, and not the same.
   */
  private static <T> boolean moreSpecific(final T a, final T b, final Classes<T> classes) {
    return classes.assignable(a, b) && !classes.assignable(b, a);
  }

  /**
   * The names a method of {@code prefix} is named for {@code property} under, in order: the prefix
   * followed by the property's name made singular, where it ends in {@code ies}, {@code s} or
   * {@code es}, and then by the name as it is. {@code lines} gives {@code addLine}, {@code
   * categories} gives {@code addCategory} and {@code addresses} gives {@code addAddress}.
   */
  public static List<String> namesFor(final String prefix, final String property) {
    final var stems = new ArrayList<String>(4);
    if (property.length() > 3 && property.endsWith("ies")) {
      stems.add(property.substring(0, property.length() - 3) + "y");
    }
    if (property.length() > 1 && property.endsWith("s")) stems.add(property.substring(0, property.length() - 1));
    if (property.length() > 2 && property.endsWith("es")) stems.add(property.substring(0, property.length() - 2));
    stems.add(property);
    final var names = new ArrayList<String>(stems.size());
    for (final var stem : stems) {
      final var name = prefix + Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
      if (!names.contains(name)) names.add(name);
    }
    return List.copyOf(names);
  }

  /**
   * Whether an element of the class named {@code qualifiedName} is a value, which a write hands to
   * the owner's adder and remover even where the elements cross unconverted: a record, an enum, a
   * {@code String}, a boxed primitive, or a JDK value type {@link MapValueTypes} builds from its
   * text. An adder cannot point such an element at its owner, and the rules it applies, such as
   * keeping each value once, are what it is there for. Any other class is a mutable bean whose
   * instances the source holds, which a write hands across as they are, through neither.
   *
   * @param qualifiedName the element class's qualified name
   * @param record whether the element class is a record
   * @param enumType whether the element class is an enum
   * @return whether the element is a value
   */
  public static boolean isValue(final String qualifiedName, final boolean record, final boolean enumType) {
    return record || enumType || MapValueTypes.isValueClass(qualifiedName);
  }

  /** Where a back-reference was decided from. */
  public enum Source {
    /** A {@code backReference} row of the mapper names it. */
    ROW,
    /** {@code @OneToMany(mappedBy = ...)} on the owner's collection names it. */
    MAPPED_BY,
  }

  /**
   * The back-reference of the elements of one collection: the element's property that points at the
   * owner and where that was decided from, both null where nothing declares one.
   *
   * @param property the element's property, or null
   * @param source where {@code property} was decided from, or null with it
   */
  public record BackReference(String property, Source source) {
    /** No back-reference. */
    public static final BackReference NONE = new BackReference(null, null);
  }

  /**
   * The back-reference of a collection's elements: the property a {@code backReference} row names,
   * else the one {@code @OneToMany(mappedBy)} names where the element has a setter for it that
   * takes the owner, else none. A property the conversion of each element already writes, by name
   * or by a row, keeps its own value and is not one.
   *
   * @param row the property a {@code backReference} row names for the element class, or null
   * @param mappedBy the property {@code @OneToMany(mappedBy)} names on the owner's collection, or
   *     null
   * @param mappedByWritable whether the element has a setter for {@code mappedBy} that takes the
   *     owner; a {@code mappedBy} it cannot be set through is passed over
   * @param converted whether the elements are converted, rather than handed across as the source
   *     holds them; elements handed across are never pointed at the owner, since they are the
   *     source's own
   * @param filled whether the conversion of each element writes a property
   */
  public static BackReference backReference(
    final String row,
    final String mappedBy,
    final boolean mappedByWritable,
    final boolean converted,
    final Predicate<String> filled
  ) {
    if (!converted) return BackReference.NONE;
    if (row != null) return new BackReference(row, Source.ROW);
    if (mappedBy != null && mappedByWritable && !filled.test(mappedBy)) {
      return new BackReference(mappedBy, Source.MAPPED_BY);
    }
    return BackReference.NONE;
  }
}
