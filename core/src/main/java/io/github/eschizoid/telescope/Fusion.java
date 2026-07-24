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
 *       the {@link Hop#key()}: {@code (owner class, component name)} for field/traverse hops (so
 *       separately-built paths still share), reference identity for filter predicates.
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
 *   <li>a trie node branching on anything but same-owner, pairwise-distinct components — a filter
 *       or narrow hop may sit in a shared prefix, but branching across one is not provably
 *       order-free (a filtered branch and a direct branch can edit the same component).
 * </ul>
 *
 * <p>Edits with EQUAL full paths do fuse: their leaf functions compose in edit order, which is
 * exactly the sequential semantics. {@code Edit.identity()} slots (a sparse-PATCH {@code
 * overIfPresent(null)}) are skipped — they contribute nothing.
 *
 * <p>A record-level slot plan routes a {@code null} focused value through the same per-segment
 * sequential ops the fallback uses, so null handling is identical to composed-lens behavior by
 * construction rather than by re-implementation.
 *
 * <p>Erasure note: the engine works in {@code Object}-typed segments. The types were proven at
 * path-build time (each segment IS the lattice optic the DSL composed); this is the same erased
 * assembly floor {@link DeepMap} sits on.
 */
final class Fusion {

  private Fusion() {}

  /**
   * One navigation hop retained for fusion, parallel to the introspection trail: the identity key
   * that drives trie sharing, the hop's un-composed optic segment, and — for component-anchored
   * hops — the owning class and component name, plus the inner container traversal for traverse
   * hops (used when the hop folds into a record slot plan).
   */
  record Hop(
    Object key,
    Traversal<Object, Object> segment,
    Class<?> owner,
    String component,
    Traversal<Object, Object> inner
  ) {
    static Hop field(final Class<?> owner, final String component, final Traversal<Object, Object> lens) {
      return new Hop(List.of(owner, component), lens, owner, component, null);
    }

    static Hop traverse(
      final Class<?> owner,
      final String component,
      final String containerKind,
      final Traversal<Object, Object> segment,
      final Traversal<Object, Object> inner
    ) {
      return new Hop(List.of(owner, component, containerKind), segment, owner, component, inner);
    }

    static Hop narrow(final Class<?> subType, final Traversal<Object, Object> prism) {
      return new Hop(List.of(subType, "as"), prism, null, null, null);
    }

    static Hop filter(final Object predicate, final Traversal<Object, Object> filtered) {
      // Reference identity: two filter hops share a trie node only when they are literally the
      // same predicate instance on a shared prefix — lambda equality cannot be decided.
      return new Hop(predicate, filtered, null, null, null);
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
      var node = root;
      for (final var hop : e.path().hops) {
        node = node.children.computeIfAbsent(hop.key(), __ -> new Node(hop));
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

    Node(final Hop hop) {
      this.hop = hop;
    }
  }

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
    final var childFns = new ArrayList<Function<Object, Object>>(kids.size());
    for (final var child : kids) {
      final var fn = compile(child);
      if (fn == null) return null;
      childFns.add(fn);
    }

    if (kids.size() == 1) {
      final var seg = kids.get(0).hop.segment();
      final var inner = childFns.get(0);
      return x -> seg.modify(x, inner);
    }

    // Branching is fusible only across same-owner, pairwise-distinct components: that is the shape
    // where cross-branch order is provably irrelevant. Filter / narrow hops carry no component and
    // therefore never participate in a branch.
    Class<?> owner = null;
    final var seenComponents = new HashSet<String>();
    for (final var child : kids) {
      final var h = child.hop;
      if (h.owner() == null || h.component() == null) return null;
      if (owner == null) owner = h.owner();
      else if (owner != h.owner()) return null;
      if (!seenComponents.add(h.component())) return null; // field vs each on one component — bail
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
}
