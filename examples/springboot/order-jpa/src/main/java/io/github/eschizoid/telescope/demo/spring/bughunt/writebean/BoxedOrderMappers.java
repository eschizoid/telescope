package io.github.eschizoid.telescope.demo.spring.bughunt.writebean;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * Per-class {@code writeBean(ShippingNote.class, CONSTRUCTOR)} demo. {@code BoxedOrderEntity} picks
 * up the {@code writeBeans(SETTERS)} default; the immutable {@link ShippingNote} leaf has no
 * setters / builder / no-arg ctor, so the per-class override is the only thing keeping the deep
 * recursion alive.
 */
public final class BoxedOrderMappers {

  private BoxedOrderMappers() {}

  public static Mapper<BoxedOrder, BoxedOrderEntity> boxedOrderMapper() {
    return Telescope.mapper(
      BoxedOrder.class,
      BoxedOrderEntity.class,
      writeBeans(SETTERS),
      writeBean(ShippingNote.class, CONSTRUCTOR)
    );
  }
}
