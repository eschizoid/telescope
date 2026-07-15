package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.function.Function;

/**
 * MethodHandle-combinator assembly of a structural conversion {@link Iso} where each side is a
 * record (canonical-constructor rebuild) or a JavaBean (no-arg constructor + setters).
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
 *   <li><b>Construct side (target).</b> A record target pipes the per-slot filters straight into
 *       the raw canonical-constructor handle via {@link MethodHandles#filterArguments} + {@link
 *       MethodHandles#permuteArguments}. A bean target folds the raw no-arg constructor handle with
 *       one raw setter per slot via {@link MethodHandles#foldArguments} — the setter runs as a void
 *       side effect and the bean instance carries through. Identity and reference slots stay
 *       unboxed; a primitive slot fed by a value-producing (non-identity) Iso is read boxed only so
 *       it can be null-guarded before unboxing (see {@code setterFromSource}).
 * </ul>
 *
 * <p><b>Lattice.</b> The result is an ordinary {@link Iso#of(Function, Function)} — the composed
 * handles <em>are</em> the leaf Iso's forward/backward transforms. Composition above this leaf
 * (nested pairs, {@code liftList}/{@code liftMapValues}, {@code .then(...)}) is unchanged and still
 * routes through the optic lattice. This sharpens the leaf; it does not bypass the lattice.
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
   * Whether the {@code source} &harr; {@code target} conversion can be composed by this assembler:
   * each side must be a record within the arity ceiling, or a bean constructible via a no-arg
   * constructor plus a public {@code setX} setter for every property of the bean (not only the
   * mapped ones — the conservative per-class gate). {@code DeepMap} consults this once, at build
   * time, to choose the composed-handle leaf over the array leaf — a shape decision, not a runtime
   * fallback. A bean that needs a builder or field injection (no no-arg constructor, or any
   * property with no setter) returns {@code false} and routes to the array leaf.
   */
  public static boolean supports(final Class<?> source, final Class<?> target) {
    // Test seam for the differential parity oracle: with the system property below set,
    // MhIsoDifferentialParityTest routes the identical conversion through the legacy array leaf and
    // asserts byte-identical output against this leaf. Unset in production; read once at build time
    // (never per conversion), so no steady-state cost.
    if (Boolean.getBoolean(DISABLE_PROPERTY)) return false;
    return constructibleBy(source) && constructibleBy(target);
  }

  /**
   * System property (test-only) that forces every pair to the legacy array leaf. See {@link
   * #supports}.
   */
  public static final String DISABLE_PROPERTY = "io.github.eschizoid.telescope.mhiso.disabled";

  private static boolean constructibleBy(final Class<?> cls) {
    if (cls.isRecord()) return cls.getRecordComponents().length <= MAX_ARITY;
    // A bean side is composable only when it has a no-arg constructor and every one of its
    // properties is writable via a setter. Requiring a setter for every property (not only the
    // mapped ones) is the conservative gate — it keeps `supports` a pure per-class question, and a
    // bean with a getter-only property is exactly the field-injection shape the array leaf must own
    // for correctness. Records rebuild every component through the canonical constructor
    // regardless.
    return Beans.isSetterConstructible(cls, Beans.propertyNames(cls));
  }

  // (Iso, Object) -> Object  ==  iso.to(v) / iso.from(v). Bound per non-identity field.
  private static final MethodHandle ISO_TO;
  private static final MethodHandle ISO_FROM;
  // (Object) -> boolean  ==  value != null. Guards a primitive bean setter against a null value.
  private static final MethodHandle NON_NULL;

  static {
    try {
      final MethodHandles.Lookup lk = MethodHandles.lookup();
      ISO_TO = lk.findVirtual(Iso.class, "to", MethodType.methodType(Object.class, Object.class));
      ISO_FROM = lk.findVirtual(Iso.class, "from", MethodType.methodType(Object.class, Object.class));
      NON_NULL = lk.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class));
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
    // Erase both directions to (Object) -> Object so the Function SAM call site can invokeExact
    // them — the boundary casts (Object -> instance on entry, instance -> Object on exit) are cheap
    // reference casts; the primitive fields inside stay unboxed.
    final MethodHandle fwd = compose(source, target, fwdSrcPos, fwdIso, ISO_TO, identity).asType(
      MethodType.methodType(Object.class, Object.class)
    );
    final MethodHandle bwd = compose(target, source, bwdTgtPos, bwdIso, ISO_FROM, identity).asType(
      MethodType.methodType(Object.class, Object.class)
    );

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
    return Iso.of(forward, backward);
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
    final Iso<Object, Object> identity
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
    return beanSetterFold(srcCls, tgtCls, srcAccessors, slotSrcPos, slotIso, isoDir, identity);
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

  private static RuntimeException rethrow(final Class<?> from, final Class<?> to, final Throwable e) {
    // Let Errors (StackOverflowError, OutOfMemoryError, linkage faults) propagate unwrapped — the
    // array leaf has no try/catch and lets them through raw; masking them as a RuntimeException
    // would defeat monitoring/recovery that classifies VM-level errors separately.
    if (e instanceof Error err) throw err;
    if (e instanceof RuntimeException re) return re;
    return new RuntimeException("Failed to convert " + from.getSimpleName() + " -> " + to.getSimpleName(), e);
  }
}
