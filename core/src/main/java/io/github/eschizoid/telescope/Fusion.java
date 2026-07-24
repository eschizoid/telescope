package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * The multi-edit fusion engine behind {@link Telescope#all(Edit[])}. Sibling of {@link DeepMap} /
 * {@link Merge}: a package-private {@code :core} engine that composes existing lattice operations —
 * it never hand-rolls structural plumbing the optics already provide.
 *
 * <p>{@code Telescope.all(over(...), over(...))} used to fold N edits into N sequential structural
 * passes: k edits under a shared {@code .each(...)} prefix rebuilt the same container k times, and
 * k disjoint field edits on one record rebuilt that record k times. Fusion unions the edits' hop
 * lists into a prefix trie and compiles ONE walk:
 *
 * <ul>
 *   <li><b>Prefix sharing</b> — a hop shared by several edits ({@code each(Team::users)}) runs its
 *       {@code modify} once, with the fused sub-edits applied per focused value. Hop identity is
 *       the kind-tagged {@link Hop#key()}: {@code (kind, owner class, component name[, container
 *       kind])} for field/traverse hops (so separately-built paths still share), the predicate
 *       object itself for filter hops — equality-based, which for lambdas means the same instance.
 *   <li><b>Sibling slot fusion</b> — when a trie node branches into field/traverse edits of
 *       pairwise-distinct components of one record, the node compiles to a single positional
 *       rebuild: read every component once (build-time-captured readers), apply each branch's edit
 *       to its slot, one cached canonical-constructor call. Traverse branches fold in as their
 *       component's slot function ({@code inner.modify}), so a record level is rebuilt once no
 *       matter how many of its components are edited.
 * </ul>
 *
 * <p><b>Semantics bar: observationally identical to the sequential fold.</b> Fusion applies only
 * where order-independence is provable, and {@link #fuse} returns {@code null} — caller falls back
 * to the sequential fold — everywhere else:
 *
 * <ul>
 *   <li>any edit that is not an {@link EditImpl} (a user-implemented {@code Edit}), or whose path
 *       carries no hop record (runtime-checked navigation like {@code fieldByName}, bridge hops,
 *       {@code from/to/using} entry points, custom lenses);
 *   <li>one edit's path a strict prefix of another's (the deeper edit runs inside the shallower
 *       one's leaf — sequential order is observable);
 *   <li>a trie node branching on anything but same-owner, pairwise-distinct components — filter and
 *       narrow hops never participate in a branch (a filtered branch and a direct branch can edit
 *       the same component).
 * </ul>
 *
 * <p><b>Filters are value-dependent and get per-edit replay.</b> The sequential fold re-tests a
 * filter's predicate between passes — an edit can change the very data the predicate reads, so a
 * later edit must see the re-tested verdict on the already-edited value. A filter node carrying two
 * or more edits therefore does NOT hoist the test: it compiles to a per-edit replay (each edit's
 * remaining path applied through its own filter test, in edit order — per element, exactly the
 * sequential work), while the container walk above the filter stays fused. Narrow hops need no
 * replay: an edit's {@code Function<B, B>} cannot change whether the prism matches. Container
 * traverses need none either: {@code modify} preserves element count and position.
 *
 * <p>Edits with EQUAL full paths fuse by composing leaf functions in edit order — sequential
 * semantics, given the filter rule above. {@code Edit.identity()} slots (a sparse-PATCH {@code
 * overIfPresent(null)}) are skipped — they contribute nothing.
 *
 * <p>A record-level slot plan routes a {@code null} focused value through the same per-segment
 * sequential ops the fallback uses, so null handling is identical to composed-lens behavior by
 * construction rather than by re-implementation.
 *
 * <p><b>Purity scope.</b> "Observationally identical" assumes pure leaf functions, predicates, and
 * record accessors — the standing optics assumption. Result values always match; what a fused pass
 * changes is internal evaluation shape (element-major instead of edit-major, slot functions in
 * component-declaration order, fewer structural rebuilds and accessor reads), which only a
 * side-effecting function could observe.
 *
 * <p>Erasure note: the engine works in {@code Object}-typed segments. The types were proven at
 * path-build time (each segment IS the lattice optic the DSL composed); this is the same erased
 * assembly floor {@link DeepMap} sits on.
 */
final class Fusion {

  private Fusion() {}

  /**
   * One navigation hop retained for fusion, parallel to the introspection trail: the hop kind, the
   * identity key that drives trie sharing (kind-tagged so key domains cannot collide), the hop's
   * un-composed optic segment, and — for component-anchored hops — the owning class and component
   * name, plus the inner container traversal for traverse hops (used when the hop folds into a
   * record slot plan).
   */
  record Hop(
    Kind kind,
    Object key,
    Traversal<Object, Object> segment,
    Class<?> owner,
    String component,
    Traversal<Object, Object> inner
  ) {
    enum Kind {
      FIELD,
      TRAVERSE,
      NARROW,
      FILTER,
    }

    static Hop field(final Class<?> owner, final String component, final Traversal<Object, Object> lens) {
      return new Hop(Kind.FIELD, List.of(Kind.FIELD, owner, component), lens, owner, component, null);
    }

    static Hop traverse(
      final Class<?> owner,
      final String component,
      final String containerKind,
      final Traversal<Object, Object> segment,
      final Traversal<Object, Object> inner
    ) {
      return new Hop(
        Kind.TRAVERSE,
        List.of(Kind.TRAVERSE, owner, component, containerKind),
        segment,
        owner,
        component,
        inner
      );
    }

    static Hop narrow(final Class<?> subType, final Traversal<Object, Object> prism) {
      return new Hop(Kind.NARROW, List.of(Kind.NARROW, subType), prism, null, null, null);
    }

    static Hop filter(final Object predicate, final Traversal<Object, Object> filtered) {
      // The predicate object is the key: sharing is equals-based, which for lambdas (no equals
      // override) means the same instance on a shared prefix — lambda equality cannot be decided.
      return new Hop(Kind.FILTER, predicate, filtered, null, null, null);
    }
  }

  /**
   * Fuse the edits into a single structural pass, or return {@code null} when fusion cannot be
   * proven observationally identical to the sequential fold (the caller falls back).
   */
  @SuppressWarnings("unchecked")
  static <S> Function<S, S> fuse(final Edit<S>[] edits) {
    final var live = new ArrayList<EditImpl<S, ?>>(edits.length);
    for (final var e : edits) {
      if (e == EditIdentity.INSTANCE) continue; // sparse-PATCH null slot — contributes nothing
      if (!(e instanceof EditImpl)) return null;
      final var impl = (EditImpl<S, ?>) e;
      if (impl.path().hops == null) return null;
      live.add(impl);
    }
    if (live.size() < 2) return null; // nothing to fuse; the sequential fold is already minimal

    // Overlap: a strict-prefix pair means the deeper edit rewrites part of the shallower edit's
    // leaf — sequential order is observable there, so fusion declines.
    final var keyLists = new ArrayList<List<Object>>(live.size());
    for (final var e : live) {
      final var keys = new ArrayList<Object>(e.path().hops.size());
      for (final var h : e.path().hops) keys.add(h.key());
      keyLists.add(keys);
    }
    for (var i = 0; i < keyLists.size(); i++) {
      for (var j = 0; j < keyLists.size(); j++) {
        if (i != j && isStrictPrefix(keyLists.get(i), keyLists.get(j))) return null;
      }
    }

    final var root = new Node(null);
    for (final var e : live) {
      final var hops = e.path().hops;
      var node = root;
      for (var i = 0; i < hops.size(); i++) {
        final var hop = hops.get(i);
        node = node.children.computeIfAbsent(hop.key(), __ -> new Node(hop));
        // A filter's verdict is value-dependent: the sequential fold re-tests it between passes,
        // so a filter node fusing >= 2 edits must replay them per edit. Record each edit's tail
        // (remaining hops + leaf fn) at every filter node it crosses, in edit order.
        if (node.hop.kind() == Hop.Kind.FILTER) {
          node.tails.add(new Tail(hops.subList(i + 1, hops.size()), (Function<Object, Object>) e.fn()));
        }
      }
      node.leafFns.add((Function<Object, Object>) e.fn());
    }

    final var compiled = compile(root);
    if (compiled == null) return null;
    return s -> (S) compiled.apply(s);
  }

  private static boolean isStrictPrefix(final List<Object> shorter, final List<Object> longer) {
    if (shorter.size() >= longer.size()) return false;
    for (var i = 0; i < shorter.size(); i++) if (!shorter.get(i).equals(longer.get(i))) return false;
    return true;
  }

  private static final class Node {

    final Hop hop; // null at the root
    final LinkedHashMap<Object, Node> children = new LinkedHashMap<>();
    final List<Function<Object, Object>> leafFns = new ArrayList<>();
    // Populated at FILTER nodes only: one entry per edit crossing (or ending at) this node, in
    // edit order — the replay data for the per-edit compile shape.
    final List<Tail> tails = new ArrayList<>();

    Node(final Hop hop) {
      this.hop = hop;
    }
  }

  /** An edit's remaining path below a filter node plus its leaf function. */
  private record Tail(List<Hop> remaining, Function<Object, Object> fn) {}

  /** Compile a trie node into the edit applied at its focus, or {@code null} when unfusible. */
  @SuppressWarnings("unchecked")
  private static Function<Object, Object> compile(final Node node) {
    if (!node.leafFns.isEmpty()) {
      // The strict-prefix check excluded leaf-and-children nodes; equal-path edits compose here in
      // edit order — exactly the sequential semantics.
      var fn = node.leafFns.get(0);
      for (var i = 1; i < node.leafFns.size(); i++) fn = fn.andThen(node.leafFns.get(i));
      return fn;
    }

    final var kids = List.copyOf(node.children.values());

    if (kids.size() == 1) return wrapChild(kids.get(0));

    // Branching is fusible only across same-owner, pairwise-distinct components: that is the shape
    // where cross-branch order is provably irrelevant. Filter / narrow hops never participate in a
    // branch — a filtered branch and a direct branch can edit the same component.
    Class<?> owner = null;
    final var seenComponents = new HashSet<String>();
    for (final var child : kids) {
      final var h = child.hop;
      if (h.kind() != Hop.Kind.FIELD && h.kind() != Hop.Kind.TRAVERSE) return null;
      if (owner == null) owner = h.owner();
      else if (owner != h.owner()) return null;
      if (!seenComponents.add(h.component())) return null; // field vs each on one component — bail
    }

    final var childFns = new ArrayList<Function<Object, Object>>(kids.size());
    for (final var child : kids) {
      final var fn = compile(child);
      if (fn == null) return null;
      childFns.add(fn);
    }

    // The per-segment sequential shape: the bean-owner compile target, and the null-source route
    // of the record slot plan (so null behavior matches composed lenses by construction).
    Function<Object, Object> sequential = Function.identity();
    for (var i = 0; i < kids.size(); i++) {
      final var seg = kids.get(i).hop.segment();
      final var fn = childFns.get(i);
      sequential = sequential.andThen(x -> seg.modify(x, fn));
    }
    if (!owner.isRecord()) return sequential;

    // Record slot plan: one positional rebuild for the whole branch level. Readers are captured at
    // compile time (no per-call name lookup); traverse branches fold in as their component's slot
    // function; untouched components copy through.
    final var comps = Records.componentNames(owner);
    final var readers = new ArrayList<Lens<Object, Object>>(comps.length);
    final var recordClass = (Class<Object>) owner;
    for (final var comp : comps) readers.add(Records.fieldLens(recordClass, comp));
    final var slotFns = new ArrayList<Function<Object, Object>>(comps.length);
    for (var i = 0; i < comps.length; i++) slotFns.add(null);
    for (var i = 0; i < kids.size(); i++) {
      final var h = kids.get(i).hop;
      final var fn = childFns.get(i);
      final var slot = List.of(comps).indexOf(h.component());
      if (slot < 0) return null; // component not on the canonical ctor — defensive; bail to sequential fold
      slotFns.set(slot, h.inner() == null ? fn : v -> h.inner().modify(v, fn));
    }
    final var cls = owner;
    final var onNull = sequential;
    final var n = comps.length;
    return x -> {
      if (x == null) return onNull.apply(x);
      final var args = new Object[n];
      for (var i = 0; i < n; i++) {
        final var v = readers.get(i).get(x);
        final var fn = slotFns.get(i);
        args[i] = fn == null ? v : fn.apply(v);
      }
      return Records.construct(cls, args);
    };
  }

  /**
   * Compile a child node into the edit applied at its PARENT's focus (the child's segment wrap).
   *
   * <p>The filter-soundness seam lives here: a filter's verdict is value-dependent, and the
   * sequential fold re-tests it between passes — an edit can change data the predicate reads, so a
   * later edit must see the re-tested verdict on the already-edited value. A filter node fusing two
   * or more edits therefore compiles to a per-edit replay: each edit's remaining path is applied
   * through its own filter test, in edit order — per focused element, exactly the work the
   * sequential fold does — while everything ABOVE the filter (the container walk) stays fused. A
   * single-edit filter node has only one pass either way and takes the normal wrap.
   */
  private static Function<Object, Object> wrapChild(final Node child) {
    final var seg = child.hop.segment();
    if (child.hop.kind() == Hop.Kind.FILTER && child.tails.size() >= 2) {
      Function<Object, Object> chain = Function.identity();
      for (final var tail : child.tails) {
        // edit order
        var fn = tail.fn();
        for (var i = tail.remaining().size() - 1; i >= 0; i--) {
          final var hopSeg = tail.remaining().get(i).segment();
          final var inner = fn;
          fn = x -> hopSeg.modify(x, inner);
        }
        final var perEdit = fn;
        chain = chain.andThen(x -> seg.modify(x, perEdit));
      }
      return chain;
    }
    final var inner = compile(child);
    if (inner == null) return null;
    return x -> seg.modify(x, inner);
  }
}
