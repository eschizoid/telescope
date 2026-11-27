package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.conversion.ServiceNeeds;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.Compute;
import io.github.eschizoid.telescope.mapping.ComputeFromSource;
import io.github.eschizoid.telescope.mapping.Conditional;
import io.github.eschizoid.telescope.mapping.ForwardOnlyTransformTo;
import io.github.eschizoid.telescope.mapping.MapStep;
import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.mapping.Served;
import io.github.eschizoid.telescope.mapping.TypedTransformTo;
import io.github.eschizoid.telescope.mapping.Via;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The rows of a mapper that call a service: which services they call, the rows rebuilt bound to
 * given services, and the name a refusal gives a mapper. A mapper binds by building itself again
 * from the bound rows, so a bound mapper converts through the same composition, and writes through
 * the same writers, as one whose rows call static methods.
 */
final class ServiceRows {

  private ServiceRows() {}

  /** The function a row holds that calls a service, or null. */
  static Served servedOf(final Mapping<?, ?> row) {
    return switch (row) {
      case ComputeFromSource<?, ?, ?> c -> c.function() instanceof Served s ? s : null;
      case Compute<?, ?, ?> c -> c.supplier() instanceof Served s ? s : null;
      case TypedTransformTo<?, ?, ?, ?> t -> t.forward() instanceof Served s ? s : null;
      case ForwardOnlyTransformTo<?, ?, ?, ?> t -> t.forward() instanceof Served s ? s : null;
      case Conditional<?, ?> c -> c.predicate() instanceof Served s ? s : servedOf(c.inner());
      default -> null;
    };
  }

  /**
   * The services {@code steps} call: each row's own, in row order, then those of each mapper a
   * {@code via} row names.
   */
  static List<ServiceNeeds.Need> needs(final MapStep[] steps) {
    final var own = new ArrayList<ServiceNeeds.Need>();
    final var nested = new ArrayList<Object>();
    for (final var step : steps) {
      if (!(step instanceof Mapping<?, ?> row)) continue;
      if (row instanceof Conditional<?, ?> c && c.predicate() instanceof Served s) own.add(need(s));
      final var served = row instanceof Conditional<?, ?> c ? servedOf(c.inner()) : servedOf(row);
      if (served != null) own.add(need(served));
      if (row instanceof Via<?, ?> via) nested.add(via.edits() ? via.editMapper() : via.nested());
    }
    return ServiceNeeds.joined(own, nested);
  }

  /**
   * The nodes {@code explain()} reports for the rows of {@code steps} that call a service, each
   * saying whether the row is bound.
   */
  static List<OpticNode> nodes(final MapStep[] steps) {
    final var nodes = new ArrayList<OpticNode>();
    for (final var step : steps) {
      if (!(step instanceof Mapping<?, ?> row)) continue;
      if (row instanceof Conditional<?, ?> c && c.predicate() instanceof Served s) {
        nodes.add(need(s).node(s.service() != null));
      }
      final var served = row instanceof Conditional<?, ?> c ? servedOf(c.inner()) : servedOf(row);
      if (served != null) nodes.add(need(served).node(served.service() != null));
    }
    return nodes;
  }

  /** {@code trail} followed by the nodes of the rows of {@code steps} that call a service. */
  static List<OpticNode> withNodes(final List<OpticNode> trail, final MapStep[] steps) {
    final var nodes = nodes(steps);
    if (nodes.isEmpty()) return trail;
    final var all = new ArrayList<>(trail);
    all.addAll(nodes);
    return all;
  }

  private static ServiceNeeds.Need need(final Served served) {
    return new ServiceNeeds.Need(served.serviceClass(), served.describe(), null, served.explain());
  }

  /** Whether any of {@code steps} calls a service, itself or through a mapper it names. */
  static boolean any(final MapStep[] steps) {
    for (final var step : steps) {
      if (!(step instanceof Mapping<?, ?> row)) continue;
      if (servedOf(row) != null) return true;
      if (
        row instanceof Via<?, ?> via &&
        !ServiceNeeds.joined(List.of(), List.of(via.edits() ? via.editMapper() : via.nested())).isEmpty()
      ) return true;
    }
    return false;
  }

  /**
   * Refuses {@code steps} for {@code factory}, which returns a value with nothing to bind a service
   * to, where a row calls one.
   */
  static void refuseUnbindable(final MapStep[] steps, final String factory) {
    for (final var step : steps) {
      if (step instanceof Mapping<?, ?> row && servedOf(row) instanceof Served served) {
        throw new IllegalArgumentException(PairingMessages.serviceOnUnbindable(factory, served.describe()));
      }
    }
  }

  /** {@code steps}, each row calling a service bound to the one {@code services} holds for it. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static MapStep[] bind(final MapStep[] steps, final Map<Class<?>, Object> services) {
    final var bound = new MapStep[steps.length];
    for (var i = 0; i < steps.length; i++) {
      bound[i] = steps[i] instanceof Mapping<?, ?> row ? bindRow((Mapping) row, services) : steps[i];
    }
    return bound;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Mapping<?, ?> bindRow(final Mapping<?, ?> row, final Map<Class<?>, Object> services) {
    return switch (row) {
      case ComputeFromSource c when c.function() instanceof Served s -> new ComputeFromSource(
        c.targetTelescope(),
        (Mapping.FromSource) bound(s, services)
      );
      case Compute c when c.supplier() instanceof Served s -> new Compute(
        c.targetTelescope(),
        (Supplier) bound(s, services)
      );
      case TypedTransformTo t when t.forward() instanceof Served s -> new TypedTransformTo(
        t.src(),
        t.tgt(),
        (Function) bound(s, services),
        t.backward()
      );
      case ForwardOnlyTransformTo t when t.forward() instanceof Served s -> new ForwardOnlyTransformTo(
        t.src(),
        t.tgt(),
        (Function) bound(s, services)
      );
      case Conditional c -> new Conditional(
        c.predicate() instanceof Served s ? (Predicate) bound(s, services) : c.predicate(),
        bindRow(c.inner(), services)
      );
      case Via via when via.edits() -> Mapping.via(
        via.src(),
        via.tgt(),
        (EditMapper<?, ?>) ServiceNeeds.bindFrom(via.editMapper(), ServiceNeeds.of(via.editMapper()), services)
      );
      case Via via -> new Via(
        via.src(),
        via.tgt(),
        (Mapper<?, ?>) ServiceNeeds.bindFrom(via.nested(), ServiceNeeds.of(via.nested()), services)
      );
      default -> row;
    };
  }

  private static Served bound(final Served served, final Map<Class<?>, Object> services) {
    return served.bind(services.get(served.serviceClass()));
  }

  /**
   * The name a refusal gives {@code mapper}: the static field of {@code owner} holding it, as
   * {@code OrderMappers.EDITS}, and {@code type} where none does. Read only when a refusal is
   * worded.
   */
  static String nameOf(final Class<?> owner, final Object mapper, final String type) {
    if (owner == null) return type;
    for (final var field : owner.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) continue;
      final var kind = field.getType();
      if (kind != Mapper.class && kind != ForwardMapper.class && kind != EditMapper.class) continue;
      try {
        field.trySetAccessible();
        if (field.get(null) == mapper) return owner.getSimpleName() + "." + field.getName();
      } catch (final IllegalAccessException | RuntimeException unreadable) {
        // A field that cannot be read names nothing; the type names the mapper instead.
      }
    }
    return type;
  }

  /**
   * The declared type of a mapper of {@code kind} between the two classes, as a message names it.
   */
  static String typeOf(final String kind, final Class<?> source, final Class<?> target) {
    return kind + "<" + source.getSimpleName() + ", " + target.getSimpleName() + ">";
  }
}
