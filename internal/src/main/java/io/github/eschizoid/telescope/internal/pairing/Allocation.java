package io.github.eschizoid.telescope.internal.pairing;

/**
 * What a declared container type is rebuilt as, and which of its constructors builds it.
 *
 * <p>The implementation is named rather than handed over as a type, because the two renderers want
 * different things from it — one binds a constructor, the other writes a {@code new} expression —
 * and a name is what they have in common.
 *
 * <p>{@link Call} says which constructor, not merely whether to size. A value that said only "size
 * it" would leave a second renderer unable to reproduce the first: {@code HashMap} and {@code
 * WeakHashMap} are both sized from a table capacity and are called differently, because one has a
 * factory that does the arithmetic and the other does not.
 */
public sealed interface Allocation {
  /** The class to build, and the constructor to build it with. */
  record Build(String implName, Call call) implements Allocation {}

  /**
   * This declared type has no constructor a rebuild can call, with the sentence to report. The
   * reason travels with the refusal so either renderer refuses in the same words.
   */
  record Refuse(String reason) implements Allocation {}

  /**
   * The constructor a rebuild calls.
   *
   * <p>Named by what is passed rather than by intent, because the intent is the same in three of
   * these and the call is not. A list takes an element count; a hash container takes a table
   * capacity, which for the same elements is a different number, and reaches it through a factory
   * where one exists and by arithmetic where it does not.
   */
  enum Call {
    /**
     * No argument: the constructor takes none, or its {@code int} means something else entirely.
     * Every class a rebuild builds as itself rather than through a table row is built this way,
     * since a constructor taking an argument carries whatever meaning its author gave it.
     */
    NO_ARG,
    /**
     * {@code new Impl(count)} — the element count, unchanged. A list and a deque take it as their
     * initial capacity; {@code IdentityHashMap} and {@code ConcurrentHashMap} size for that many
     * entries internally, so they take the same number and the same text, and dividing it by a load
     * factor first would over-allocate them.
     */
    COUNT,
    /** {@code Impl.newImpl(count)} — the JDK factory that sizes a table for that many elements. */
    TABLE_FACTORY,
    /**
     * {@code new Impl(capacity)} — the same table sizing where the JDK ships no factory, so the
     * caller computes the capacity. The arithmetic is the factories' own: the smallest capacity at
     * which that many entries fit under a 0.75 load factor, which is {@code (int) Math.ceil(count /
     * 0.75)}.
     */
    TABLE_ARITHMETIC,
    /**
     * A container that keeps an order rather than a size, built from the order its source carries.
     *
     * <p>The comparator is the source <em>instance</em>'s, not one implied by its declared type: a
     * field written as a plain {@code Map} can hold one ordered by a comparator. A renderer with no
     * instance to read therefore emits the read rather than performing it — {@code src instanceof
     * SortedMap<?, ?> s ? s.comparator() : null} — since the declared type need not have a {@code
     * comparator()} to call.
     *
     * <p>A set's comparator orders the elements themselves, so it transfers only while those keep
     * their type. Where a conversion changes them, a rebuild is refused rather than reordered, and
     * the refusal names {@code Mapping.via(...)} with a target comparator as the way through. It is
     * conditional on the source instance: a sorted set in natural order carries no comparator to
     * invalidate, so it converts like any other set.
     */
    ORDERING,
    /**
     * {@code new Impl(Key.class)} — a container built from the class of its keys, which is how an
     * {@code EnumMap} learns the enum it is over. The class is the declared key type, so a
     * declaration that names none is refused rather than built.
     */
    KEY_CLASS,
  }
}
