package io.github.eschizoid.telescope.declaredbridge;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-ins for what the processor writes for a {@code @Bridge} field: a bridge over a pair, and a
 * provider naming the class that declares the field, registered through {@code META-INF/services}.
 * The bridges count their calls, so a test can tell the generated path from the runtime one.
 */
public final class DeclaredFixtures {

  private DeclaredFixtures() {}

  public record Point(String name, String note) {}

  public record PointDto(String label, String note) {}

  public record Stale(String name) {}

  public record StaleDto(String label) {}

  public record Forward(String name) {}

  public record Moved(String name, String alias) {}

  public record MovedDto(String label) {}

  /** A bridge generated when the row read {@code alias}; the field's row now reads {@code name}. */
  public static final Telescope<Moved, MovedDto> MOVED_BRIDGE = Telescope.bridge(
    new BridgeFn<Moved, MovedDto>() {
      @Override
      public MovedDto forward(final Moved s) {
        GENERATED_CALLS.incrementAndGet();
        return new MovedDto(s.alias());
      }

      @Override
      public Moved backward(final MovedDto t) {
        GENERATED_CALLS.incrementAndGet();
        return new Moved(t.label(), t.label());
      }
    }
  );

  /**
   * A class whose field's row names another accessor than the one its bridge was generated from.
   */
  public static final class MovedMappers {

    private MovedMappers() {}

    public static final Mapper<Moved, MovedDto> MOVED = Telescope.mapper(
      Moved.class,
      MovedDto.class,
      to(Moved::name, MovedDto::label),
      drop(Moved::alias)
    );
  }

  public static final class MovedProvider implements BridgeProvider {

    @Override
    public Class<?> sourceType() {
      return Moved.class;
    }

    @Override
    public Class<?> targetType() {
      return MovedDto.class;
    }

    @Override
    public Class<?> declaringType() {
      return MovedMappers.class;
    }

    @Override
    public List<String> rowKinds() {
      return List.of("SameTypedTo", "Drop");
    }

    @Override
    public List<String> rowAccessors() {
      return List.of("alias>label", "name>");
    }

    @Override
    public Object bridge() {
      return MOVED_BRIDGE;
    }
  }

  public record ForwardDto(String label) {}

  /** Calls into the stand-in generated bridges. */
  public static final AtomicInteger GENERATED_CALLS = new AtomicInteger();

  /**
   * The bridge a field declares for {@code Point -> PointDto}: {@code name} renamed to {@code
   * label}.
   */
  public static final class PointFn implements BridgeFn<Point, PointDto> {

    @Override
    public PointDto forward(final Point s) {
      GENERATED_CALLS.incrementAndGet();
      return s == null ? null : new PointDto(s.name(), s.note());
    }

    @Override
    public Point backward(final PointDto t) {
      GENERATED_CALLS.incrementAndGet();
      return t == null ? null : new Point(t.label(), t.note());
    }

    @Override
    public Point patch(final Point base, final PointDto partial) {
      GENERATED_CALLS.incrementAndGet();
      return new Point(
        partial.label() == null ? base.name() : partial.label(),
        partial.note() == null ? base.note() : partial.note()
      );
    }

    @Override
    public boolean declaresPair() {
      return true;
    }
  }

  public static final Telescope<Point, PointDto> POINT_BRIDGE = Telescope.bridge(new PointFn());

  public static final Telescope<Stale, StaleDto> STALE_BRIDGE = Telescope.bridge(
    new BridgeFn<Stale, StaleDto>() {
      @Override
      public StaleDto forward(final Stale s) {
        GENERATED_CALLS.incrementAndGet();
        return new StaleDto(s.name());
      }

      @Override
      public Stale backward(final StaleDto t) {
        return new Stale(t.label());
      }
    }
  );

  public static final Telescope<Forward, ForwardDto> FORWARD_BRIDGE = Telescope.bridge(
    new BridgeFn<Forward, ForwardDto>() {
      @Override
      public ForwardDto forward(final Forward s) {
        GENERATED_CALLS.incrementAndGet();
        return s == null ? null : new ForwardDto(s.name());
      }

      @Override
      public Forward backward(final ForwardDto t) {
        throw new UnsupportedOperationException();
      }
    }
  );

  /** The class whose field the processor would have read. */
  public static final class PointMappers {

    private PointMappers() {}

    public static final Mapper<Point, PointDto> POINT = Telescope.mapper(
      Point.class,
      PointDto.class,
      to(Point::name, PointDto::label)
    );

    /** The same mapper built by a method of the declaring class, which no field initialises. */
    public static Mapper<Point, PointDto> later() {
      return Telescope.mapper(Point.class, PointDto.class, to(Point::name, PointDto::label));
    }
  }

  /** Another class building the same mapper, which no field of its own declares. */
  public static final class OtherMappers {

    private OtherMappers() {}

    public static Mapper<Point, PointDto> point() {
      return Telescope.mapper(Point.class, PointDto.class, to(Point::name, PointDto::label));
    }
  }

  /** A class whose recorded rows are not the rows it builds its mapper from. */
  public static final class StaleMappers {

    private StaleMappers() {}

    public static final Mapper<Stale, StaleDto> STALE = Telescope.mapper(
      Stale.class,
      StaleDto.class,
      to(Stale::name, StaleDto::label)
    );
  }

  public static final class ForwardMappers {

    private ForwardMappers() {}

    public static final ForwardMapper<Forward, ForwardDto> FORWARD = Telescope.mapperForward(
      Forward.class,
      ForwardDto.class,
      to(Forward::name, ForwardDto::label)
    );
  }

  public static final class PointProvider implements BridgeProvider {

    @Override
    public Class<?> sourceType() {
      return Point.class;
    }

    @Override
    public Class<?> targetType() {
      return PointDto.class;
    }

    @Override
    public Class<?> declaringType() {
      return PointMappers.class;
    }

    @Override
    public List<String> rowKinds() {
      return List.of("SameTypedTo");
    }

    @Override
    public List<String> rowAccessors() {
      return List.of("name>label");
    }

    @Override
    public List<String> targetProperties() {
      return List.of("label", "note");
    }

    @Override
    public Object bridge() {
      return POINT_BRIDGE;
    }
  }

  public static final class StaleProvider implements BridgeProvider {

    @Override
    public Class<?> sourceType() {
      return Stale.class;
    }

    @Override
    public Class<?> targetType() {
      return StaleDto.class;
    }

    @Override
    public Class<?> declaringType() {
      return StaleMappers.class;
    }

    @Override
    public List<String> rowKinds() {
      return List.of("SameTypedTo", "Drop");
    }

    @Override
    public Object bridge() {
      return STALE_BRIDGE;
    }
  }

  public static final class ForwardProvider implements BridgeProvider {

    @Override
    public Class<?> sourceType() {
      return Forward.class;
    }

    @Override
    public Class<?> targetType() {
      return ForwardDto.class;
    }

    @Override
    public Class<?> declaringType() {
      return ForwardMappers.class;
    }

    @Override
    public List<String> rowKinds() {
      return List.of("SameTypedTo");
    }

    @Override
    public List<String> rowAccessors() {
      return List.of("name>label");
    }

    @Override
    public Object bridge() {
      return FORWARD_BRIDGE;
    }
  }
}
