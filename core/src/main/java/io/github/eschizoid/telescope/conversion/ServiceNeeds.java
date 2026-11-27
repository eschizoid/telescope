package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <b>Module-internal seam — NOT public API.</b> The services a mapper's rows call, its own and
 * those of the mappers it converts through, whether it is bound to them, and how a copy bound to
 * other ones is built. A mapper whose rows call no service holds none.
 *
 * <p>An unbound mapper refuses every operation before it reads anything, naming the first row that
 * calls a service; {@code with(...)} returns a copy bound to the services given, refusing a null, a
 * value no service accepts, two values for one service and a service not given.
 */
public final class ServiceNeeds {

  /**
   * One service a mapper needs: its class, the row that calls it, as it is written, and the mapper
   * that row belongs to where it is not the mapper itself, by its field's name or its type.
   */
  public record Need(Class<?> service, String row, String in) {
    public Need {
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(row, "row");
    }
  }

  /**
   * What a binder builds a bound copy from: the needs the copy holds, bound, and the value of each
   * class of service.
   */
  public record Binding(ServiceNeeds needs, Map<Class<?>, Object> services) {}

  private final Supplier<List<Need>> needs;
  private volatile List<Need> known;
  private final Function<Object, String> name;
  private final String type;
  private final Object[] bound;
  private final Function<Binding, Object> binder;

  private ServiceNeeds(
    final Supplier<List<Need>> needs,
    final Function<Object, String> name,
    final String type,
    final Object[] bound,
    final Function<Binding, Object> binder
  ) {
    this.needs = needs;
    this.name = name;
    this.type = type;
    this.bound = bound;
    this.binder = binder;
  }

  /**
   * The services of an unbound mapper. {@code needs} lists them, read the first time they are
   * asked for, since a mapper converting through another one declared after it reads that one
   * only once its class has initialised. {@code name} names the mapper given it, {@code type} is
   * its declared type, and {@code binder} builds the copy bound to the values in the order {@link
   * #classes()} lists them, handed them also by class.
   */
  public static ServiceNeeds unbound(
    final Supplier<List<Need>> needs,
    final Function<Object, String> name,
    final String type,
    final Function<Binding, Object> binder
  ) {
    return new ServiceNeeds(needs, name, type, null, binder);
  }

  /** These services, bound to {@code values}, which are in the order {@link #classes()} lists. */
  public ServiceNeeds bound(final Object[] values) {
    return new ServiceNeeds(this::needs, name, type, values.clone(), binder);
  }

  /** Every need, in the order the rows and the mappers they convert through list them. */
  public List<Need> needs() {
    var list = known;
    if (list == null) {
      list = List.copyOf(needs.get());
      known = list;
    }
    return list;
  }

  /** The classes of service, each once, in the order {@link #needs()} first names them. */
  public List<Class<?>> classes() {
    final var classes = new ArrayList<Class<?>>();
    for (final var need : needs()) if (!classes.contains(need.service())) classes.add(need.service());
    return List.copyOf(classes);
  }

  /** Whether this is bound, or needs nothing. */
  public boolean satisfied() {
    return bound != null || needs().isEmpty();
  }

  /** The bound values, in the order {@link #classes()} lists them; null when unbound. */
  public Object[] values() {
    return bound == null ? null : bound.clone();
  }

  /** The value bound for {@code service}, or null. */
  public Object valueOf(final Class<?> service) {
    if (bound == null) return null;
    final var classes = classes();
    final var at = classes.indexOf(service);
    return at < 0 ? null : bound[at];
  }

  /** The type the mapper is declared as, as {@code EditMapper<OrderChanges, Order>}. */
  public String type() {
    return type;
  }

  /**
   * Refuses a call on {@code mapper} while it is not bound and needs a service, naming the first
   * row that calls one.
   *
   * @throws IllegalStateException when the mapper is not bound
   */
  public void requireBound(final Object mapper) {
    if (bound != null) return;
    final var all = needs();
    if (all.isEmpty()) return;
    final var first = all.getFirst();
    throw new IllegalStateException(
      PairingMessages.serviceUnbound(
        name.apply(mapper),
        type,
        PairingMessages.className(first.service()),
        first.row(),
        first.in()
      )
    );
  }

  /** The name a message gives {@code mapper}: its field, or its type. */
  public String nameOf(final Object mapper) {
    return name.apply(mapper);
  }

  /**
   * A copy of {@code mapper} bound to {@code values}, each matched to the service it is an instance
   * of.
   *
   * @throws IllegalArgumentException for a null, a value no service accepts, two values for one
   *     service, or a service not given
   */
  public Object bind(final Object mapper, final Object... values) {
    Objects.requireNonNull(values, "values");
    final var classes = classes();
    final var who = name.apply(mapper);
    if (classes.isEmpty()) {
      refuseAny(who, values);
      return mapper;
    }
    final var names = classes.stream().map(PairingMessages::className).toList();
    final var ordered = new Object[classes.size()];
    for (var i = 0; i < values.length; i++) {
      final var value = values[i];
      if (value == null) throw new IllegalArgumentException(PairingMessages.serviceWithNull(who, i + 1, names));
      var taken = false;
      for (var c = 0; c < classes.size(); c++) {
        if (!classes.get(c).isInstance(value)) continue;
        taken = true;
        if (ordered[c] != null && ordered[c] != value) throw new IllegalArgumentException(
          PairingMessages.serviceWithRepeated(
            who,
            names.get(c),
            PairingMessages.className(ordered[c].getClass()),
            PairingMessages.className(value.getClass())
          )
        );
        ordered[c] = value;
      }
      if (!taken) throw new IllegalArgumentException(
        PairingMessages.serviceWithUnneeded(who, PairingMessages.className(value.getClass()), names)
      );
    }
    for (var c = 0; c < classes.size(); c++) {
      if (ordered[c] != null) continue;
      final var service = classes.get(c);
      final var need = needs()
        .stream()
        .filter(n -> n.service() == service)
        .findFirst()
        .orElseThrow();
      throw new IllegalArgumentException(
        PairingMessages.serviceWithMissing(who, names.get(c), need.row(), need.in())
      );
    }
    return binder.apply(new Binding(bound(ordered), byClass(classes, ordered)));
  }

  /**
   * A copy of {@code mapper}, which holds {@code needs}, bound to the values {@code services} holds
   * for each of its classes; {@code mapper} itself where it needs none.
   */
  public static Object bindFrom(final Object mapper, final ServiceNeeds needs, final Map<Class<?>, Object> services) {
    if (needs == null || needs.needs().isEmpty()) return mapper;
    final var classes = needs.classes();
    final var ordered = new Object[classes.size()];
    for (var c = 0; c < classes.size(); c++) {
      ordered[c] = services.get(classes.get(c));
      if (ordered[c] == null) throw new IllegalStateException(
        "No " + PairingMessages.className(classes.get(c)) + " was handed on to " + needs.nameOf(mapper)
      );
    }
    return needs.binder.apply(new Binding(needs.bound(ordered), byClass(classes, ordered)));
  }

  /**
   * Refuses {@code values} handed to {@code with(...)} on {@code who}, a mapper whose rows call no
   * service: a null and any other value alike.
   *
   * @throws IllegalArgumentException for the first value
   */
  public static void refuseAny(final String who, final Object... values) {
    Objects.requireNonNull(values, "values");
    if (values.length == 0) return;
    if (values[0] == null) throw new IllegalArgumentException(PairingMessages.serviceWithNull(who, 1, List.of()));
    throw new IllegalArgumentException(
      PairingMessages.serviceWithUnneeded(who, PairingMessages.className(values[0].getClass()), List.of())
    );
  }

  private static Map<Class<?>, Object> byClass(final List<Class<?>> classes, final Object[] values) {
    final var map = new LinkedHashMap<Class<?>, Object>();
    for (var c = 0; c < classes.size(); c++) map.put(classes.get(c), values[c]);
    return map;
  }

  /**
   * The needs of the mappers {@code nested} lists, each by the name its own needs give it, after
   * {@code own}; a mapper listed twice counts once.
   */
  public static List<Need> joined(final List<Need> own, final List<?> nested) {
    final var all = new ArrayList<>(own);
    final var seen = new IdentityHashMap<Object, Boolean>();
    for (final var mapper : nested) {
      if (seen.put(mapper, Boolean.TRUE) != null) continue;
      final var their = of(mapper);
      if (their == null) continue;
      final var in = their.nameOf(mapper);
      for (final var need : their.needs()) all.add(new Need(need.service(), need.row(), need.in() == null ? in : need.in()));
    }
    return all;
  }

  /** The needs {@code mapper} holds, or null for a value that is not a mapper or needs none. */
  public static ServiceNeeds of(final Object mapper) {
    return switch (mapper) {
      case Mapper<?, ?> m -> m.serviceNeeds();
      case ForwardMapper<?, ?> m -> m.serviceNeeds();
      case EditMapper<?, ?> m -> m.serviceNeeds();
      case null, default -> null;
    };
  }
}
