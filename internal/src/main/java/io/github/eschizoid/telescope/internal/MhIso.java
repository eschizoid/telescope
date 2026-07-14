package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;

/**
 * MethodHandle-combinator assembly of a record&harr;record conversion {@link Iso}.
 *
 * <p>The array-based assembly in {@code DeepMap.assembleIso} allocates an {@code Object[]} per call
 * and boxes every primitive component (its readers are typed {@code Function<Object, Object>} and
 * its builder spreads an {@code Object[]}). This assembler instead composes the whole conversion
 * into a single {@code (S) -> T} handle: each constructor argument is produced by running the
 * source's raw, primitive-typed accessor handle, piped straight into the target's raw constructor
 * handle via {@link MethodHandles#filterArguments} + {@link MethodHandles#permuteArguments}. On
 * same-name/same-type ("identity") fields the value flows primitive-to-primitive with no box and no
 * array; only fields carrying a real per-field {@link Iso} (rename with conversion, nested pair,
 * container lift) route through that Iso, exactly as before.
 *
 * <p><b>Lattice.</b> The result is an ordinary {@link Iso#of(Function, Function)} — the composed
 * handles <em>are</em> the leaf Iso's forward/backward transforms. Composition above this leaf
 * (nested pairs, {@code liftList}/{@code liftMapValues}, {@code .then(...)}) is unchanged and still
 * routes through the optic lattice. This sharpens the leaf; it does not bypass the lattice.
 */
public final class MhIso {

  /**
   * Constructor-parameter ceiling for {@code filterArguments}/{@code permuteArguments} composition.
   */
  private static final int MAX_ARITY = 250;

  private MhIso() {}

  /**
   * Whether {@code source} and {@code target} are both records within the arity this assembler can
   * compose. {@code DeepMap} consults this once, at build time, to choose the composed-handle leaf
   * over the array leaf — a shape decision, not a runtime fallback.
   */
  public static boolean supports(final Class<?> source, final Class<?> target) {
    return (
      source.isRecord() &&
      target.isRecord() &&
      source.getRecordComponents().length <= MAX_ARITY &&
      target.getRecordComponents().length <= MAX_ARITY
    );
  }

  // (Iso, Object) -> Object  ==  iso.to(v) / iso.from(v). Bound per non-identity field.
  private static final MethodHandle ISO_TO;
  private static final MethodHandle ISO_FROM;

  static {
    try {
      final MethodHandles.Lookup lk = MethodHandles.lookup();
      ISO_TO = lk.findVirtual(Iso.class, "to", MethodType.methodType(Object.class, Object.class));
      ISO_FROM = lk.findVirtual(Iso.class, "from", MethodType.methodType(Object.class, Object.class));
    } catch (final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Build the record&harr;record conversion as an {@link Iso} whose forward/backward are single
   * composed handles. The slot arrays are the same ones {@code DeepMap.buildSlotMaps} produces:
   * {@code fwdSrcPos[i]} is the source position feeding target slot {@code i} ({@code -1} = no
   * source), and {@code fwdIso[i]} is that slot's per-field Iso ({@code == identity} for a plain
   * same-name/same-type passthrough). Backward is symmetric.
   */
  public static <S, T> Iso<S, T> recordPair(
    final Class<S> source,
    final Class<T> target,
    final int[] fwdSrcPos,
    final Iso<Object, Object>[] fwdIso,
    final int[] bwdTgtPos,
    final Iso<Object, Object>[] bwdIso,
    final Iso<Object, Object> identity
  ) {
    final Records.RecordInfo srcInfo = Records.info(source);
    final Records.RecordInfo tgtInfo = Records.info(target);

    // Erase both directions to (Object) -> Object so the Function SAM call site can invokeExact
    // them — the boundary casts (Object -> record on entry, record -> Object on exit) are cheap
    // reference casts; the primitive fields inside stay unboxed.
    final MethodHandle fwd = compose(
      source,
      target,
      srcInfo.accessorHandles(),
      tgtInfo.ctorHandle(),
      fwdSrcPos,
      fwdIso,
      ISO_TO,
      identity
    ).asType(MethodType.methodType(Object.class, Object.class));
    final MethodHandle bwd = compose(
      target,
      source,
      tgtInfo.accessorHandles(),
      srcInfo.ctorHandle(),
      bwdTgtPos,
      bwdIso,
      ISO_FROM,
      identity
    ).asType(MethodType.methodType(Object.class, Object.class));

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
   * Compose {@code (srcCls) -> tgtCls}: for each target constructor parameter, a filter handle
   * {@code (srcCls) -> paramType} produced from the source accessor (and the slot's per-field Iso
   * when it is not the identity), then {@code filterArguments} into the constructor and {@code
   * permuteArguments} to feed the single source instance to every filter.
   */
  private static MethodHandle compose(
    final Class<?> srcCls,
    final Class<?> tgtCls,
    final MethodHandle[] srcAccessors,
    final MethodHandle tgtCtor,
    final int[] slotSrcPos,
    final Iso<Object, Object>[] slotIso,
    final MethodHandle isoDir,
    final Iso<Object, Object> identity
  ) {
    final Class<?>[] paramTypes = tgtCtor.type().parameterArray();
    final MethodHandle[] filters = new MethodHandle[paramTypes.length];
    for (var i = 0; i < paramTypes.length; i++) {
      final Class<?> pt = paramTypes[i];
      final int sp = slotSrcPos[i];
      final boolean isIdentity = slotIso[i] == identity;
      if (isIdentity && sp >= 0) {
        // Plain passthrough: raw accessor straight into the constructor slot,
        // primitive-to-primitive,
        // no box. This is the fast path the whole assembler exists for.
        filters[i] = srcAccessors[sp].asType(MethodType.methodType(pt, srcCls));
      } else if (isIdentity) {
        // Identity Iso but no source field: yield null. asType into a primitive slot unboxes null →
        // NPE at call time, identical to the array path's `ctorFn.apply(nullSlot)`.
        filters[i] = MethodHandles.dropArguments(MethodHandles.constant(Object.class, null), 0, srcCls).asType(
          MethodType.methodType(pt, srcCls)
        );
      } else {
        // Non-identity Iso (rename-with-conversion, nested pair, container lift, constant, compute,
        // when-gate): mirror the array path's `iso.to(v)`, where v is the read value or null when
        // the
        // slot has no source. isoStep : (Object) -> Object.
        final MethodHandle isoStep = isoDir.bindTo(slotIso[i]);
        if (sp < 0) {
          // v == null: constant / compute / gated rows produce their value from a null input.
          filters[i] = MethodHandles.dropArguments(
            MethodHandles.insertArguments(isoStep, 0, (Object) null),
            0,
            srcCls
          ).asType(MethodType.methodType(pt, srcCls));
        } else {
          final Class<?> readType = srcAccessors[sp].type().returnType();
          filters[i] = MethodHandles.filterReturnValue(
            srcAccessors[sp],
            isoStep.asType(MethodType.methodType(Object.class, readType))
          ).asType(MethodType.methodType(pt, srcCls));
        }
      }
    }
    final MethodHandle filtered = MethodHandles.filterArguments(tgtCtor, 0, filters);
    final int[] toSingleInput = new int[paramTypes.length]; // all zeros: every filter reads slot 0
    return MethodHandles.permuteArguments(filtered, MethodType.methodType(tgtCls, srcCls), toSingleInput);
  }

  private static RuntimeException rethrow(final Class<?> from, final Class<?> to, final Throwable e) {
    if (e instanceof RuntimeException re) return re;
    return new RuntimeException("Failed to convert " + from.getSimpleName() + " -> " + to.getSimpleName(), e);
  }
}
