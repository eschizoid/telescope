package io.github.eschizoid.telescope.jpa.fixtures;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The mappers that write a basket in place: two through generated bridges, two built at run time
 * over the same shape. Each pair converts the lines and tags element by element through a nested
 * pair, and fills a basket through {@link Basket#addLine} and {@link Basket#addTag}, which no row
 * names.
 */
public final class BasketMappers {

  private BasketMappers() {}

  /** {@code FORM.into(basket, form)}, compiled by {@code telescope-codegen}. */
  @Bridge
  public static final Mapper<BasketForm, Basket> FORM = Telescope.mapper(BasketForm.class, Basket.class);

  /** {@code PATCH.patchInto(basket, form)}, compiled by {@code telescope-codegen}. */
  @Bridge
  public static final Mapper<Basket, BasketForm> PATCH = Telescope.mapper(Basket.class, BasketForm.class);

  /** {@code EDIT.into(basket, edit)}, built at run time. */
  public static final Mapper<BasketEdit, Basket> EDIT = Telescope.mapper(BasketEdit.class, Basket.class);

  /** {@code EDIT_PATCH.patchInto(basket, edit)}, built at run time. */
  public static final Mapper<Basket, BasketEdit> EDIT_PATCH = Telescope.mapper(Basket.class, BasketEdit.class);
}
