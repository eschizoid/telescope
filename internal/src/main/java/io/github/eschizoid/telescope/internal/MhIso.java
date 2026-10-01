package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * MethodHandle-combinator assembly of a structural conversion {@link Iso} where each side is a
 * record (canonical-constructor rebuild) or a JavaBean built the way its path's writer builds it:
 * through its setters, a constructor, or its builder.
 *
 * <p>The array-based assembly in {@code DeepMap.assembleIso} allocates an {@code Object[]} per call
 * and boxes every primitive component (its readers are typed {@code Function<Object, Object>} and
 * its builder spreads an {@code Object[]}). This assembler instead composes the whole conversion
 * into a single {@code (S) -> T} handle. Both directions have two independent halves:
 *
 * <ul>
 *   <li><b>Read side (source).</b> A raw, primitive-typed accessor handle per property in {@code
 *       names(...)} order — {@code Records.RecordInfo.accessorHandles} for a record, {@code
 *       Beans.beanAccessorHandles} for a bean. Same-name/same-type ("identity") slots read
 *       primitive-to-primitive with no box; only slots carrying a real per-field {@link Iso}
 *       (rename with conversion, nested pair, container lift, constant, compute, when-gate) route
 *       through that Iso.
 *   <li><b>Construct side (target).</b> A record target, and a bean built through a constructor,
 *       pipe the per-slot filters straight into the raw constructor handle via {@link
 *       MethodHandles#filterArguments} + {@link MethodHandles#permuteArguments}. A bean built
 *       through its setters folds the raw no-arg constructor handle with one raw setter per slot
 *       via {@link MethodHandles#foldArguments} — the setter runs as a void side effect and the
 *       bean instance carries through; a bean built through its builder folds the builder's members
 *       over the builder instance the same way and finishes with {@code build()}. Identity and
 *       reference slots stay unboxed; a primitive slot that can be handed null is read boxed only
 *       so it can be null-guarded before unboxing (see {@code setterFromSource} and {@code
 *       argumentFromSource}).
 * </ul>
 *
 * <p><b>Lattice.</b> The result is a {@code Leaf} — an {@link Iso} whose forward/backward
 * <em>are</em> the composed handles (wrapped as null-guarded {@code Function}s, exactly as {@link
 * Iso#of(Function, Function)} would), and which additionally exposes those raw {@code
 * (Object)->Object} handles so a parent container lift can loop over them directly (see {@link
 * #liftCollection}/{@link #liftMap}). Composition above this leaf (nested pairs, {@code
 * liftList}/{@code liftMapValues}, {@code .then(...)}) is unchanged and still routes through the
 * optic lattice. This sharpens the leaf; it does not bypass the lattice.
 */
public final class MhIso {

  /**
   * Constructor-parameter ceiling for {@code filterArguments}/{@code permuteArguments} composition
   * of a record target. Bean targets fold one setter per slot and have no comparable arity limit,
   * but the source read side of a bean is still bounded by the number of properties, well under
   * this ceiling in practice.
   */
  private static final int MAX_ARITY = 250;

  private MhIso() {}

  /**
   * Whether the {@code source} &harr; {@code target} conversion can be composed with each bean side
   * built through its setters: each side must be a record within the arity ceiling, or a bean with
   * a no-arg constructor plus a public {@code setX} setter for every property. This is the shape
   * the seven-argument {@link #pair} composes; {@code DeepMap} instead asks {@link #composesBuild}
   * per direction, with the writer that direction's path would pick.
   */
  public static boolean supports(final Class<?> source, final Class<?> target) {
    return composesBuild(source, target, settersOrNull(target)) && composesBuild(target, source, settersOrNull(source));
  }

  /**
   * Whether this assembler can compose the one direction that reads {@code from} and builds {@code
   * to} with {@code writer}, the writer that direction's path picks for {@code to} ({@code null}
   * for a record). {@code from} must be a record within the arity ceiling or any bean, since a bean
   * is read through its getters. {@code to} must be a record within the ceiling, or a bean whose
   * writer the assembler reproduces exactly:
   *
   * <ul>
   *   <li>setters, when the bean has a no-arg constructor and a public setter for every property —
   *       requiring every property rather than only the mapped ones keeps this a per-class
   *       question, and a getter-only property is a shape the array leaf owns;
   *   <li>a constructor, when every argument it reads by name is a property;
   *   <li>a builder, always: its members are called for the properties it has one for, in property
   *       order, and {@code build()}'s result is checked as the writer checks it.
   * </ul>
   *
   * <p>No writer routes the direction to the array leaf. The composed build makes the same calls in
   * the same order as the writer, so the two produce the same value. {@code DeepMap} consults this
   * once per direction, at build time — a shape decision, not a runtime fallback.
   */
  public static boolean composesBuild(final Class<?> from, final Class<?> to, final Beans.BeanWriter<?> writer) {
    // Test seam for the differential parity oracle: with the system property below set,
    // MhIsoDifferentialParityTest routes the identical conversion through the legacy array leaf and
    // asserts byte-identical output against this leaf. Unset in production; read once at build time
    // (never per conversion), so no steady-state cost.
    if (Boolean.getBoolean(DISABLE_PROPERTY)) return false;
    if (from.isRecord() && from.getRecordComponents().length > MAX_ARITY) return false;
    if (to.isRecord()) return to.getRecordComponents().length <= MAX_ARITY;
    return switch (writer) {
      case Beans.SettersWriter<?> ignored -> Beans.isSetterConstructible(to, Beans.propertyNames(to));
      case Beans.ConstructorWriter<?> constructor -> argumentsAreProperties(to, constructor);
      case Beans.BuilderWriter<?> ignored -> true;
      case null -> false;
    };
  }

  /**
   * System property (test-only) that forces every pair to the legacy array leaf. See {@link
   * #composesBuild}.
   */
  public static final String DISABLE_PROPERTY = "io.github.eschizoid.telescope.mhiso.disabled";

  /**
   * Whether {@code iso} is a composed-handle {@code Leaf} this assembler produced (so it carries
   * raw forward/backward handles). {@code DeepMap} consults this to hand a parent pair the concrete
   * leaf for an acyclic nested slot — enabling full-tree fusion in {@link #pair}'s filters —
   * instead of a proxy that would force an {@code Iso.to} &rarr; {@code Function.apply} hop per
   * nested object.
   */
  public static boolean isComposedLeaf(final Iso<?, ?> iso) {
    // Test seam: with FUSION_DISABLE_PROPERTY set, DeepMap hands the parent the null-guarding proxy
    // rather than the leaf, so pair's filter routes the nested conversion through Iso.to over that
    // same leaf (the non-fused path). MhFusionParityTest flips this to prove fusion is
    // byte-identical.
    if (Boolean.getBoolean(FUSION_DISABLE_PROPERTY)) return false;
    return iso instanceof Leaf<?, ?>;
  }

  /**
   * System property (test-only) that forces a nested-pair slot back to the proxy dispatch (no
   * fusion) while keeping the same underlying leaf. See {@link #isComposedLeaf}.
   */
  public static final String FUSION_DISABLE_PROPERTY = "io.github.eschizoid.telescope.mhiso.fusion.disabled";

  /**
   * The setter writer for a bean, or {@code null} for a record or a bean it cannot be built for.
   */
  private static Beans.BeanWriter<?> settersOrNull(final Class<?> cls) {
    if (cls.isRecord()) return null;
    try {
      return Beans.settersWriter(cls);
    } catch (final RuntimeException e) {
      return null;
    }
  }

  /**
   * Whether every name {@code writer} reads an argument by is a property of {@code cls}. The array
   * leaf looks each up among the target's slots and has no slot for any other name.
   */
  private static boolean argumentsAreProperties(final Class<?> cls, final Beans.ConstructorWriter<?> writer) {
    final var props = Beans.propertyNames(cls);
    for (final var name : writer.argumentNames(props)) {
      if (indexOf(props, name) < 0) return false;
    }
    return true;
  }

  private static int indexOf(final String[] names, final String name) {
    for (var i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
    return -1;
  }

  // (Iso, Object) -> Object  ==  iso.to(v) / iso.from(v). Bound per non-identity field.
  private static final MethodHandle ISO_TO;
  private static final MethodHandle ISO_FROM;
  // (Object) -> boolean  ==  value != null. Guards a primitive bean setter against a null value.
  private static final MethodHandle NON_NULL;
  // Combinator primitives for the container-element loops (liftCollection / liftMap), all erased to
  // Object receivers so the loop bodies read/write through the raw element handle with no box.
  private static final MethodHandle ITERABLE_ITERATOR; // (Object) -> Iterator      Iterable.iterator
  private static final MethodHandle COLLECTION_ADD; //     (Object, Object) -> boolean Collection.add
  private static final MethodHandle MAP_ENTRYSET; //       (Object) -> Object        Map.entrySet
  private static final MethodHandle MAP_PUT; //            (Object, Object, Object) -> Object Map.put
  private static final MethodHandle ENTRY_KEY; //          (Object) -> Object        Map.Entry.getKey
  private static final MethodHandle ENTRY_VALUE; //        (Object) -> Object      Map.Entry.getValue
  private static final MethodHandle ALLOCATE; // (Function, Object) -> Object
  // (Throwable, Object, Class, Class) -> Object : relabels a fused nested-conversion failure.
  private static final MethodHandle THROW_CONVERSION;

  static {
    try {
      final MethodHandles.Lookup lk = MethodHandles.lookup();
      ISO_TO = lk.findVirtual(Iso.class, "to", MethodType.methodType(Object.class, Object.class));
      ISO_FROM = lk.findVirtual(Iso.class, "from", MethodType.methodType(Object.class, Object.class));
      NON_NULL = lk.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class));
      ITERABLE_ITERATOR = lk
        .findVirtual(Iterable.class, "iterator", MethodType.methodType(Iterator.class))
        .asType(MethodType.methodType(Iterator.class, Object.class));
      COLLECTION_ADD = lk
        .findVirtual(Collection.class, "add", MethodType.methodType(boolean.class, Object.class))
        .asType(MethodType.methodType(boolean.class, Object.class, Object.class));
      MAP_ENTRYSET = lk
        .findVirtual(Map.class, "entrySet", MethodType.methodType(Set.class))
        .asType(MethodType.methodType(Object.class, Object.class));
      MAP_PUT = lk
        .findVirtual(Map.class, "put", MethodType.methodType(Object.class, Object.class, Object.class))
        .asType(MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
      ENTRY_KEY = lk
        .findVirtual(Map.Entry.class, "getKey", MethodType.methodType(Object.class))
        .asType(MethodType.methodType(Object.class, Object.class));
      ENTRY_VALUE = lk
        .findVirtual(Map.Entry.class, "getValue", MethodType.methodType(Object.class))
        .asType(MethodType.methodType(Object.class, Object.class));
      ALLOCATE = lk.findVirtual(Function.class, "apply", MethodType.methodType(Object.class, Object.class));
      THROW_CONVERSION = lk.findStatic(
        MhIso.class,
        "throwConversion",
        MethodType.methodType(Object.class, Throwable.class, Class.class, Class.class)
      );
    } catch (final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Build the {@code source} &harr; {@code target} conversion as an {@link Iso} whose
   * forward/backward are single composed handles. Each side is dispatched by shape (record vs bean)
   * on both the read half and the construct half. The slot arrays are the same ones {@code
   * DeepMap.buildSlotMaps} produces: {@code fwdSrcPos[i]} is the source position feeding target
   * slot {@code i} ({@code -1} = no source), and {@code fwdIso[i]} is that slot's per-field Iso
   * ({@code == identity} for a plain same-name/same-type passthrough). Backward is symmetric.
   */
  public static <S, T> Iso<S, T> pair(
    final Class<S> source,
    final Class<T> target,
    final int[] fwdSrcPos,
    final Iso<Object, Object>[] fwdIso,
    final int[] bwdTgtPos,
    final Iso<Object, Object>[] bwdIso,
    final Iso<Object, Object> identity
  ) {
    return pair(
      source,
      target,
      fwdSrcPos,
      fwdIso,
      bwdTgtPos,
      bwdIso,
      identity,
      null,
      true,
      true,
      settersOrNull(target),
      settersOrNull(source)
    );
  }

  /**
   * {@link #pair} with each direction composed only when {@code composeForward} / {@code
   * composeBackward} says so, building its side with the writer given for it; a direction left out
   * runs through {@code other} instead. {@code DeepMap} composes a direction when {@link
   * #composesBuild} accepts the writer that direction's path picks for the side it builds, and
   * hands the other direction to the array leaf, so a pair whose source no composed build
   * reproduces still composes the forward direction, which only reads that source.
   *
   * <p>The result is still a composed leaf: a direction run through {@code other} exposes its raw
   * handle as {@code other}'s own transform, so a parent fuses it the same way.
   *
   * @param targetWriter the writer the forward direction builds {@code target} with, or {@code
   *     null} for a record
   * @param sourceWriter the writer the backward direction builds {@code source} with, or {@code
   *     null} for a record
   */
  public static <S, T> Iso<S, T> pair(
    final Class<S> source,
    final Class<T> target,
    final int[] fwdSrcPos,
    final Iso<Object, Object>[] fwdIso,
    final int[] bwdTgtPos,
    final Iso<Object, Object>[] bwdIso,
    final Iso<Object, Object> identity,
    final Iso<Object, Object> other,
    final boolean composeForward,
    final boolean composeBackward,
    final Beans.BeanWriter<?> targetWriter,
    final Beans.BeanWriter<?> sourceWriter
  ) {
    // Erase both directions to (Object) -> Object so the Function SAM call site can invokeExact
    // them — the boundary casts (Object -> instance on entry, instance -> Object on exit) are cheap
    // reference casts; the primitive fields inside stay unboxed.
    final var erased = MethodType.methodType(Object.class, Object.class);
    final MethodHandle fwd = composeForward
      ? compose(source, target, fwdSrcPos, fwdIso, ISO_TO, identity, targetWriter).asType(erased)
      : ISO_TO.bindTo(other).asType(erased);
    final MethodHandle bwd = composeBackward
      ? compose(target, source, bwdTgtPos, bwdIso, ISO_FROM, identity, sourceWriter).asType(erased)
      : ISO_FROM.bindTo(other).asType(erased);

    final Function<S, T> forward = s -> {
      if (s == null) return null;
      try {
        @SuppressWarnings("unchecked")
        final T out = (T) fwd.invokeExact((Object) s);
        return out;
      } catch (final Throwable e) {
        throw rethrow(source, target, e);
      }
    };
    final Function<T, S> backward = t -> {
      if (t == null) return null;
      try {
        @SuppressWarnings("unchecked")
        final S out = (S) bwd.invokeExact((Object) t);
        return out;
      } catch (final Throwable e) {
        throw rethrow(target, source, e);
      }
    };
    // Return a Leaf carrier rather than a bare Iso.of: it behaves identically as an Iso (to/from
    // delegate to the null-guarded Functions above) but also carries the raw (Object)->Object
    // composed handles and the element source/target classes, so a parent's container slot can loop
    // over the handles directly (liftCollection / liftMap) and label a per-element conversion
    // failure
    // with the real classes. The raw handles carry NO null guard (that lives in forward/backward);
    // the container loops add a per-element null guard so a null element maps to null, matching the
    // Java path's iso.to(null) == null.
    return new Leaf<>(source, target, forward, backward, fwd, bwd);
  }

  /**
   * The Iso returned by {@link #pair}: an ordinary structural-conversion {@link Iso} that also
   * exposes its raw {@code (Object)->Object} forward/backward handles and its element source/target
   * classes. A parent leaf that holds this as a container element's Iso ({@code List<Leaf>}, {@code
   * Set<Leaf>}, {@code Map<K, Leaf>}) can loop over {@link #rawForward()} / {@link #rawBackward()}
   * with a MethodHandle loop instead of dispatching {@code Iso.to} &rarr; {@code Function.apply}
   * per element, and label a per-element failure with {@link #sourceClass()} / {@link
   * #targetClass()}.
   */
  private record Leaf<S, T>(
    Class<S> sourceClass,
    Class<T> targetClass,
    Function<S, T> forward,
    Function<T, S> backward,
    MethodHandle rawFwd,
    MethodHandle rawBwd
  ) implements Iso<S, T> {
    @Override
    public T to(final S source) {
      return forward.apply(source);
    }

    @Override
    public S from(final T value) {
      return backward.apply(value);
    }

    MethodHandle rawForward() {
      return rawFwd;
    }

    MethodHandle rawBackward() {
      return rawBwd;
    }
  }

  /**
   * Compose {@code (srcCls) -> tgtCls}. First build one filter handle {@code (srcCls) -> slotType}
   * per target property (identity slot => raw source accessor; else route through the slot's
   * per-field Iso), then hand those filters to the record-constructor combinator or the bean
   * setter-fold combinator depending on the target's shape.
   */
  private static MethodHandle compose(
    final Class<?> srcCls,
    final Class<?> tgtCls,
    final int[] slotSrcPos,
    final Iso<Object, Object>[] slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity,
    final Beans.BeanWriter<?> writer
  ) {
    final MethodHandle[] srcAccessors = accessorHandlesFor(srcCls);
    if (tgtCls.isRecord()) {
      final MethodHandle tgtCtor = Records.info(tgtCls).ctorHandle();
      final Class<?>[] slotTypes = tgtCtor.type().parameterArray();
      final MethodHandle[] filters = buildFilters(
        srcCls,
        slotTypes,
        srcAccessors,
        slotSrcPos,
        slotIso,
        isoDir,
        identity
      );
      final MethodHandle filtered = MethodHandles.filterArguments(tgtCtor, 0, filters);
      final int[] toSingleInput = new int[slotTypes.length]; // all zeros: every filter reads slot 0
      return MethodHandles.permuteArguments(filtered, MethodType.methodType(tgtCls, srcCls), toSingleInput);
    }
    return switch (writer) {
      case Beans.ConstructorWriter<?> constructor -> beanConstructorBuild(
        srcCls,
        tgtCls,
        srcAccessors,
        slotSrcPos,
        slotIso,
        isoDir,
        identity,
        constructor
      );
      case Beans.BuilderWriter<?> builder -> beanBuilderFold(
        srcCls,
        tgtCls,
        srcAccessors,
        slotSrcPos,
        slotIso,
        isoDir,
        identity,
        builder
      );
      case Beans.SettersWriter<?> ignored -> beanSetterFold(
        srcCls,
        tgtCls,
        srcAccessors,
        slotSrcPos,
        slotIso,
        isoDir,
        identity
      );
    };
  }

  /**
   * A bean built through its constructor, as {@code ConstructorWriter} builds it: one filter per
   * parameter, reading the property the writer reads that argument by, piped into the constructor
   * handle like a record's canonical constructor. A failure in the constructor is wrapped as the
   * writer wraps it.
   */
  private static MethodHandle beanConstructorBuild(
    final Class<?> srcCls,
    final Class<?> beanCls,
    final MethodHandle[] srcAccessors,
    final int[] slotSrcPos,
    final Iso<Object, Object>[] slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity,
    final Beans.ConstructorWriter<?> writer
  ) {
    final String[] props = Beans.propertyNames(beanCls);
    final String[] keys = writer.argumentNames(props);
    final MethodHandle ctor = writer.composableConstructor();
    final Class<?>[] params = ctor.type().parameterArray();
    final MethodHandle[] filters = new MethodHandle[params.length];
    for (var j = 0; j < params.length; j++) {
      final var slot = indexOf(props, keys[j]);
      filters[j] = argumentFromSource(
        srcCls,
        params[j],
        srcAccessors,
        slotSrcPos[slot],
        slotIso[slot],
        isoDir,
        identity,
        writer.nullArgument(j)
      );
    }
    final MethodHandle filtered = MethodHandles.filterArguments(ctor, 0, filters);
    final MethodHandle single = MethodHandles.permuteArguments(
      filtered,
      MethodType.methodType(filtered.type().returnType(), srcCls),
      new int[params.length]
    );
    return single.asType(MethodType.methodType(beanCls, srcCls));
  }

  /**
   * A bean built through its builder, as {@code BuilderWriter} builds it: {@code builder()}, then
   * the member for each property the builder has one for, in property order and with its return
   * discarded, then {@code build()} and the writer's check on what it returns. The members fold
   * over the builder instance the way the setter fold folds over the bean.
   */
  private static MethodHandle beanBuilderFold(
    final Class<?> srcCls,
    final Class<?> beanCls,
    final MethodHandle[] srcAccessors,
    final int[] slotSrcPos,
    final Iso<Object, Object>[] slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity,
    final Beans.BuilderWriter<?> writer
  ) {
    final String[] props = Beans.propertyNames(beanCls);
    final Class<?> builderCls = writer.builderType();
    MethodHandle mk = MethodHandles.dropArguments(writer.composableFactory(), 0, srcCls);
    for (var i = 0; i < props.length; i++) {
      final MethodHandle member = writer.composableMember(props[i]);
      if (member == null) continue;
      final Class<?> slotType = member.type().parameterType(1);
      final MethodHandle value = argumentFromSource(
        srcCls,
        slotType,
        srcAccessors,
        slotSrcPos[i],
        slotIso[i],
        isoDir,
        identity,
        writer.nullArgument(props[i])
      );
      final MethodHandle setFromS = MethodHandles.filterArguments(member, 1, value);
      final MethodHandle populate = MethodHandles.foldArguments(
        MethodHandles.dropArguments(MethodHandles.identity(builderCls), 1, srcCls),
        setFromS
      );
      mk = MethodHandles.foldArguments(populate, mk);
    }
    return MethodHandles.filterReturnValue(mk, writer.composableBuild()).asType(MethodType.methodType(beanCls, srcCls));
  }

  /**
   * {@code (srcCls) -> paramType}: the value a constructor parameter or builder member takes for
   * one slot. A primitive parameter that the slot can hand null — any slot but an identity read of
   * a primitive source — reads the value boxed and takes {@code nullValue}, its JLS default, in
   * place of null, as the constructor and builder writers do; every other parameter reads as the
   * record path does.
   */
  private static MethodHandle argumentFromSource(
    final Class<?> srcCls,
    final Class<?> paramType,
    final MethodHandle[] srcAccessors,
    final int sp,
    final Iso<Object, Object> slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity,
    final Object nullValue
  ) {
    final var primitiveRead = slotIso == identity && sp >= 0 && srcAccessors[sp].type().returnType().isPrimitive();
    if (!paramType.isPrimitive() || primitiveRead) {
      return buildFilter(srcCls, paramType, srcAccessors, sp, slotIso, isoDir, identity);
    }
    final MethodHandle boxed = buildFilter(srcCls, Object.class, srcAccessors, sp, slotIso, isoDir, identity);
    final MethodHandle orDefault = MethodHandles.guardWithTest(
      NON_NULL,
      MethodHandles.identity(Object.class),
      MethodHandles.dropArguments(MethodHandles.constant(Object.class, nullValue), 0, Object.class)
    );
    return MethodHandles.filterReturnValue(boxed, orDefault).asType(MethodType.methodType(paramType, srcCls));
  }

  /** Raw, primitive-typed accessor handles for {@code cls} in {@code names(...)} order. */
  private static MethodHandle[] accessorHandlesFor(final Class<?> cls) {
    return cls.isRecord() ? Records.info(cls).accessorHandles() : Beans.beanAccessorHandles(cls);
  }

  /**
   * One filter handle {@code (srcCls) -> slotType[i]} per target slot. Identity slots with a source
   * read the raw accessor straight through, primitive-to-primitive, no box — the fast path this
   * assembler exists for. Every other slot mirrors the array path's {@code iso.to(v)} / {@code
   * iso.from(v)}, including the {@code sp < 0} case where {@code v == null} (constant / compute /
   * gated rows produce their value from a null input — this rule is load-bearing; do not drop it).
   */
  private static MethodHandle[] buildFilters(
    final Class<?> srcCls,
    final Class<?>[] slotTypes,
    final MethodHandle[] srcAccessors,
    final int[] slotSrcPos,
    final Iso<Object, Object>[] slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity
  ) {
    final MethodHandle[] filters = new MethodHandle[slotTypes.length];
    for (var i = 0; i < slotTypes.length; i++) {
      filters[i] = buildFilter(srcCls, slotTypes[i], srcAccessors, slotSrcPos[i], slotIso[i], isoDir, identity);
    }
    return filters;
  }

  /** The single-slot filter — factored out so the record ctor path and bean fold path share it. */
  private static MethodHandle buildFilter(
    final Class<?> srcCls,
    final Class<?> slotType,
    final MethodHandle[] srcAccessors,
    final int sp,
    final Iso<Object, Object> slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity
  ) {
    final boolean isIdentity = slotIso == identity;
    if (isIdentity && sp >= 0) {
      // Plain passthrough: raw accessor straight into the slot, primitive-to-primitive, no box.
      return srcAccessors[sp].asType(MethodType.methodType(slotType, srcCls));
    } else if (isIdentity) {
      // Identity Iso but no source field: yield null. asType into a primitive slot unboxes null →
      // NPE at call time, identical to the array path's null value flowing into the slot.
      return MethodHandles.dropArguments(MethodHandles.constant(Object.class, null), 0, srcCls).asType(
        MethodType.methodType(slotType, srcCls)
      );
    }
    // Full-tree fusion: a nested-pair slot whose Iso is itself a composed-handle Leaf inlines that
    // leaf's raw (Object)->Object handle directly into this handle — no per-nested-object
    // slotIso.to -> Function.apply hop. A per-element null guard reproduces the leaf's own null
    // short-circuit (a null nested source maps to null), and a catchException relabels a nested
    // conversion failure with the leaf's own classes (matching the proxy path's Leaf.forward wrap,
    // and the container lift's containerIso relabel) instead of letting it surface under the root
    // pair's classes. Because sub-leaves are built the same way, their own nested slots are already
    // fused into their raw handle, so this fuses the whole acyclic subtree bottom-up. Only
    // reachable
    // for sp >= 0 (a Leaf converts a real source value); DeepMap hands the Leaf through for acyclic
    // pairs only (cyclic pairs keep the cycle-guarding proxy, never a Leaf).
    if (sp >= 0 && slotIso instanceof Leaf<?, ?> leaf) {
      final boolean forward = isoDir == ISO_TO;
      final MethodHandle raw = forward ? leaf.rawForward() : leaf.rawBackward();
      final Class<?> from = forward ? leaf.sourceClass() : leaf.targetClass();
      final Class<?> to = forward ? leaf.targetClass() : leaf.sourceClass();
      final MethodHandle rawStep = guardElement(labelFailures(raw, from, to));
      final Class<?> leafReadType = srcAccessors[sp].type().returnType();
      return MethodHandles.filterReturnValue(
        srcAccessors[sp],
        rawStep.asType(MethodType.methodType(Object.class, leafReadType))
      ).asType(MethodType.methodType(slotType, srcCls));
    }
    // Non-identity Iso (rename-with-conversion, nested pair, container lift, constant, compute,
    // when-gate): mirror the array path's iso.to(v), where v is the read value or null when the
    // slot has no source. isoStep : (Object) -> Object.
    final MethodHandle isoStep = isoDir.bindTo(slotIso);
    if (sp < 0) {
      // v == null: constant / compute / gated rows produce their value from a null input.
      return MethodHandles.dropArguments(MethodHandles.insertArguments(isoStep, 0, (Object) null), 0, srcCls).asType(
        MethodType.methodType(slotType, srcCls)
      );
    }
    final Class<?> readType = srcAccessors[sp].type().returnType();
    return MethodHandles.filterReturnValue(
      srcAccessors[sp],
      isoStep.asType(MethodType.methodType(Object.class, readType))
    ).asType(MethodType.methodType(slotType, srcCls));
  }

  /**
   * Compose {@code (srcCls) -> beanCls} as a setter fold. Start from {@code mk : (srcCls) ->
   * beanCls} that drops its source argument and runs the no-arg constructor. For each writable
   * property {@code i} (its setter {@code set_i : (beanCls, Pi) -> void} and the same per-slot
   * filter {@code readVal_i : (srcCls) -> Pi} the record path builds):
   *
   * <ol>
   *   <li>{@code set_i_fromS = filterArguments(set_i, 1, readVal_i)} : {@code (beanCls, srcCls) ->
   *       void} — the setter now takes the source instance in place of the raw value.
   *   <li>{@code populate_i = foldArguments(dropArguments(identity(beanCls), 1, srcCls),
   *       set_i_fromS)} : {@code (beanCls, srcCls) -> beanCls} — runs the void setter as a side
   *       effect, then returns arg0 (the bean).
   *   <li>{@code mk = foldArguments(populate_i, mk)} : {@code (srcCls) -> beanCls} — feeds the bean
   *       built so far and the source into the populate step.
   * </ol>
   *
   * <p>Properties in {@code names(...)} order are folded in turn; {@code MhIso.supports} guarantees
   * a setter for every one of them, so no slot is silently dropped. Identity primitive slots stay
   * unboxed (source primitive, never null); a primitive slot fed by a non-identity Iso is read
   * boxed and null-guarded in {@code setterFromSource}, matching the array leaf's {@code
   * SettersWriter}.
   */
  private static MethodHandle beanSetterFold(
    final Class<?> srcCls,
    final Class<?> beanCls,
    final MethodHandle[] srcAccessors,
    final int[] slotSrcPos,
    final Iso<Object, Object>[] slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity
  ) {
    final String[] props = Beans.propertyNames(beanCls);
    MethodHandle mk = MethodHandles.dropArguments(Beans.beanNoArgCtorHandle(beanCls), 0, srcCls);
    for (var i = 0; i < props.length; i++) {
      final MethodHandle discovered = Beans.beanSetterHandle(beanCls, props[i]);
      final Class<?> slotType = discovered.type().parameterType(1);
      // An inherited setter (declared on a superclass) is unreflected against its DECLARING class,
      // so
      // its receiver type is that superclass, not beanCls. The fold produces a beanCls instance and
      // foldArguments requires the combiner's receiver to match — narrow the receiver to beanCls (a
      // safe upcast on invoke; no-op when the setter is declared on beanCls itself).
      final MethodHandle rawSetter = discovered.asType(discovered.type().changeParameterType(0, beanCls));
      // Fluent / chained setters (Lombok @Accessors(chain=true), builder-style beans) return the
      // bean rather than void. foldArguments needs a void combiner, so drop any returned value via
      // asType(void) — a plain void setter is unchanged by this.
      final MethodHandle setter = rawSetter.asType(rawSetter.type().changeReturnType(void.class));
      final MethodHandle setFromS = setterFromSource(
        srcCls,
        beanCls,
        slotType,
        setter,
        srcAccessors,
        slotSrcPos[i],
        slotIso[i],
        isoDir,
        identity
      );
      final MethodHandle populate = MethodHandles.foldArguments(
        MethodHandles.dropArguments(MethodHandles.identity(beanCls), 1, srcCls),
        setFromS
      );
      mk = MethodHandles.foldArguments(populate, mk);
    }
    return mk.asType(MethodType.methodType(beanCls, srcCls));
  }

  /**
   * Build {@code (beanCls, srcCls) -> void} — read the slot value from the source and apply {@code
   * setter}. For a <b>primitive</b> setter fed by a <b>non-identity</b> Iso the value may be null
   * (a user transform returning null), and unboxing null would NPE. The array leaf's {@code
   * SettersWriter} instead <em>skips</em> the setter on null, leaving the JLS default; this mirrors
   * that by reading the value boxed and guarding the setter with a null test. Identity primitive
   * slots (source primitive, never null) keep the unboxed fast path; reference setters accept null
   * as the array leaf does.
   */
  private static MethodHandle setterFromSource(
    final Class<?> srcCls,
    final Class<?> beanCls,
    final Class<?> slotType,
    final MethodHandle setter,
    final MethodHandle[] srcAccessors,
    final int sp,
    final Iso<Object, Object> slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity
  ) {
    if (slotType.isPrimitive() && slotIso != identity) {
      final MethodHandle readBoxed = buildFilter(srcCls, Object.class, srcAccessors, sp, slotIso, isoDir, identity);
      final MethodType guardType = MethodType.methodType(void.class, beanCls, Object.class);
      final MethodHandle doSet = setter.asType(guardType); // unboxes the (non-null) Object into the primitive
      final MethodHandle skip = MethodHandles.empty(guardType);
      final MethodHandle test = MethodHandles.dropArguments(NON_NULL, 0, beanCls);
      final MethodHandle guarded = MethodHandles.guardWithTest(test, doSet, skip);
      return MethodHandles.filterArguments(guarded, 1, readBoxed);
    }
    final MethodHandle readVal = buildFilter(srcCls, slotType, srcAccessors, sp, slotIso, isoDir, identity);
    return MethodHandles.filterArguments(setter, 1, readVal);
  }

  /**
   * Sharpen a {@code Collection}-of-element container lift: when {@code elementIso} is a {@link
   * Leaf} (a record/bean pair this assembler composed), build the {@code List}/{@code Set}
   * conversion as a MethodHandle {@link MethodHandles#iteratedLoop} that invokes the leaf's raw
   * {@code (Object)->Object} handle per element — no {@code Iso.to} &rarr; {@code Function.apply}
   * SAM hop in the loop body. {@code srcAlloc}/{@code tgtAlloc} produce the concrete source/target
   * collection (the same allocators {@code DeepMap} uses), so iteration order and collection type
   * are preserved.
   *
   * <p>Returns {@code null} when {@code elementIso} is not a Leaf (a scalar element, or an element
   * on the array leaf) — the caller keeps its plain Java-loop lift for those. Mirrors the {@link
   * #supports} / {@link #pair} probe-then-build split: this is a build-time sharpening, never a
   * runtime fallback. Also returns {@code null} when {@link #CONTAINER_DISABLE_PROPERTY} is set
   * (the test seam that routes a Leaf element back through the Java loop for differential parity).
   */
  public static Iso<Object, Object> liftCollection(
    final Iso<Object, Object> elementIso,
    final Function<Object, Object> srcAlloc,
    final Function<Object, Object> tgtAlloc
  ) {
    if (Boolean.getBoolean(CONTAINER_DISABLE_PROPERTY)) return null;
    if (!(elementIso instanceof Leaf<?, ?> leaf)) return null;
    final MethodHandle fwd = collectionLoop(guardElement(leaf.rawForward()), tgtAlloc);
    final MethodHandle bwd = collectionLoop(guardElement(leaf.rawBackward()), srcAlloc);
    return containerIso(fwd, bwd, leaf.sourceClass(), leaf.targetClass());
  }

  /**
   * System property (test-only) that forces every container lift back to its caller's Java loop
   * even for a Leaf element, so {@code MhContainerLoopParityTest} can compare the MethodHandle loop
   * against the Java loop over the identical Leaf. Unset in production; read once at build time.
   */
  public static final String CONTAINER_DISABLE_PROPERTY = "io.github.eschizoid.telescope.mhiso.container.disabled";

  /**
   * Sharpen a {@code Map}-values container lift the same way {@link #liftCollection} sharpens
   * List/Set: a MethodHandle loop over the source map's entry set that puts {@code key ->
   * rawElement(value)} into a fresh target map. Keys pass through verbatim (matching {@code
   * Iso.liftMapValues}); only values route through the Leaf's raw handle. Returns {@code null} when
   * the value element is not a Leaf (or the container test seam is set).
   */
  public static Iso<Object, Object> liftMap(
    final Iso<Object, Object> valueIso,
    final Function<Object, Object> srcAlloc,
    final Function<Object, Object> tgtAlloc
  ) {
    if (Boolean.getBoolean(CONTAINER_DISABLE_PROPERTY)) return null;
    if (!(valueIso instanceof Leaf<?, ?> leaf)) return null;
    final MethodHandle fwd = mapLoop(guardElement(leaf.rawForward()), tgtAlloc);
    final MethodHandle bwd = mapLoop(guardElement(leaf.rawBackward()), srcAlloc);
    return containerIso(fwd, bwd, leaf.sourceClass(), leaf.targetClass());
  }

  /**
   * Wrap two {@code (Object)->Object} whole-container loop handles as an {@link Iso}. The null
   * guard for a null container reference stays here (outside the loop), mirroring the Java lifts'
   * {@code xs == null ? null} head; a null container round-trips to null. {@code elemSrc}/{@code
   * elemTgt} are the container element's source/target classes, so a per-element conversion failure
   * is labelled with the real types (and the right direction) exactly as the Java lift's {@code
   * elementIso.to} / {@code from} would — the forward direction converts {@code elemSrc ->
   * elemTgt}, the backward {@code elemTgt -> elemSrc}.
   */
  private static Iso<Object, Object> containerIso(
    final MethodHandle fwd,
    final MethodHandle bwd,
    final Class<?> elemSrc,
    final Class<?> elemTgt
  ) {
    return Iso.of(
      xs -> {
        if (xs == null) return null;
        try {
          return (Object) fwd.invokeExact(xs);
        } catch (final Throwable e) {
          throw rethrow(elemSrc, elemTgt, e);
        }
      },
      ys -> {
        if (ys == null) return null;
        try {
          return (Object) bwd.invokeExact(ys);
        } catch (final Throwable e) {
          throw rethrow(elemTgt, elemSrc, e);
        }
      }
    );
  }

  /**
   * Per-element null guard: {@code element == null ? null : raw(element)}. A container may hold
   * null elements; the Java lift calls {@code elementIso.to(null)} which returns null (the Leaf's
   * forward Function short-circuits on null). The raw handle has no such guard — apply one here so
   * the loop body matches byte-for-byte.
   */
  private static MethodHandle guardElement(final MethodHandle rawElem) {
    final MethodType t = MethodType.methodType(Object.class, Object.class);
    final MethodHandle nullConst = MethodHandles.dropArguments(
      MethodHandles.constant(Object.class, null),
      0,
      Object.class
    );
    return MethodHandles.guardWithTest(NON_NULL, rawElem.asType(t), nullConst);
  }

  /**
   * Wrap {@code (Object)->Object} {@code raw} so a throwable it raises is relabelled with {@code
   * from -> to} — the leaf's own classes — via {@link #rethrow}, matching the wrap the proxy path
   * applies through {@code Leaf.forward}/{@code backward}. The catch is a MethodHandle combinator,
   * so the fused handle stays one composed tree with no SAM boundary; the happy path pays nothing.
   */
  private static MethodHandle labelFailures(final MethodHandle raw, final Class<?> from, final Class<?> to) {
    // THROW_CONVERSION is (Throwable, Class, Class) -> Object; binding from/to at positions 1,2
    // leaves (Throwable) -> Object — a legal catchException handler (the handler may accept just
    // the exception and an empty prefix of the target's parameters).
    final MethodHandle handler = MethodHandles.insertArguments(THROW_CONVERSION, 1, from, to);
    return MethodHandles.catchException(
      raw.asType(MethodType.methodType(Object.class, Object.class)),
      Throwable.class,
      handler
    );
  }

  /**
   * {@code (Throwable, Class, Class) -> Object} catch handler; {@code from}/{@code to} are bound as
   * constants at combinator-build time, leaving a {@code (Throwable) -> Object} handler that always
   * throws, relabelling the failure via {@link #rethrow}.
   */
  private static Object throwConversion(final Throwable t, final Class<?> from, final Class<?> to) {
    throw rethrow(from, to, t);
  }

  /**
   * Build {@code (Object srcColl) -> Object tgtColl}: allocate a fresh target collection, iterate
   * the source, and {@code add(element(x))} for each. {@code element} is the null-guarded raw
   * handle.
   */
  private static MethodHandle collectionLoop(final MethodHandle element, final Function<Object, Object> tgtAlloc) {
    // init : (Object srcColl) -> Object tgtColl  == fresh target collection using source
    // size/comparator.
    final MethodHandle init = ALLOCATE.bindTo(tgtAlloc);
    // add(acc, element(x)) as a void side effect: (Object acc, Object x) -> void.
    final MethodHandle mapped = MethodHandles.filterArguments(COLLECTION_ADD, 1, element);
    final MethodHandle addVoid = mapped.asType(mapped.type().changeReturnType(void.class));
    // body : (Object acc, Object x, Object srcColl) -> Object acc  (run add, return the
    // accumulator).
    final MethodHandle retAcc = MethodHandles.dropArguments(MethodHandles.identity(Object.class), 1, Object.class);
    final MethodHandle body2 = MethodHandles.foldArguments(retAcc, addVoid);
    final MethodHandle body = MethodHandles.dropArguments(body2, 2, Object.class);
    return MethodHandles.iteratedLoop(ITERABLE_ITERATOR, init, body).asType(
      MethodType.methodType(Object.class, Object.class)
    );
  }

  /**
   * Build {@code (Object srcMap) -> Object tgtMap}: allocate a fresh target map, iterate the
   * source's entry set, and {@code put(entry.key, element(entry.value))} for each. Keys pass
   * through unchanged.
   */
  private static MethodHandle mapLoop(final MethodHandle element, final Function<Object, Object> tgtAlloc) {
    // iterator : (Object srcMap) -> Iterator over the entry set.
    final MethodHandle iterator = MethodHandles.filterReturnValue(MAP_ENTRYSET, ITERABLE_ITERATOR);
    // init : (Object srcMap) -> Object tgtMap.
    final MethodHandle init = ALLOCATE.bindTo(tgtAlloc);
    // put(acc, entry.key, element(entry.value)) as a void side effect.
    final MethodHandle valFromEntry = MethodHandles.filterReturnValue(ENTRY_VALUE, element);
    final MethodHandle putFromEntries = MethodHandles.filterArguments(MAP_PUT, 1, ENTRY_KEY, valFromEntry);
    // putFromEntries : (Object acc, Object entryForKey, Object entryForVal) -> Object; feed the
    // same
    // entry to both slots, drop the returned previous-value.
    final MethodHandle putEntry = MethodHandles.permuteArguments(
      putFromEntries.asType(putFromEntries.type().changeReturnType(void.class)),
      MethodType.methodType(void.class, Object.class, Object.class),
      0,
      1,
      1
    );
    // body : (Object acc, Object entry, Object srcMap) -> Object acc.
    final MethodHandle retAcc = MethodHandles.dropArguments(MethodHandles.identity(Object.class), 1, Object.class);
    final MethodHandle body2 = MethodHandles.foldArguments(retAcc, putEntry);
    final MethodHandle body = MethodHandles.dropArguments(body2, 2, Object.class);
    return MethodHandles.iteratedLoop(iterator, init, body).asType(MethodType.methodType(Object.class, Object.class));
  }

  private static RuntimeException rethrow(final Class<?> from, final Class<?> to, final Throwable e) {
    // Let Errors (StackOverflowError, OutOfMemoryError, linkage faults) propagate unwrapped — the
    // array leaf has no try/catch and lets them through raw; masking them as a RuntimeException
    // would defeat monitoring/recovery that classifies VM-level errors separately.
    if (e instanceof Error err) throw err;
    if (e instanceof RuntimeException re) return re;
    return new RuntimeException("Failed to convert " + from.getSimpleName() + " -> " + to.getSimpleName(), e);
  }
}
