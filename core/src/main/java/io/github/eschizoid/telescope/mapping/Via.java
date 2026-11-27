package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Nested-mapper correspondence row from {@link Mapping#via(Accessor, Accessor, Mapper)}. {@code
 * DeepMap} reads the nested {@link Mapper}'s public {@code forward} / {@code backward} / {@code
 * sourceClass} / {@code targetClass} and assembles the lifted Iso itself, so no internal optic type
 * appears on this record's surface. The accessor / mapper type parameters are erased ({@code ?})
 * because the same {@code via(...)} call can either supply a matching-shape mapper or an
 * element-level mapper that telescope lifts through the accessor's container shape at
 * row-processing time (see {@code DeepMap.fieldIsoOf}).
 *
 * <p>Internal — users construct via {@link Mapping#via(Accessor, Accessor, Mapper)} and never see
 * this type at the call site.
 *
 * <p>{@code editMapper} is the {@link EditMapper} whose mapper {@code nested} is, where the row
 * names one, which only {@code Telescope.edits} accepts: it converts forward alone. Null otherwise.
 */
public record Via<A, B>(
  Accessor<A, ?> src,
  Accessor<B, ?> tgt,
  Mapper<?, ?> nested,
  EditMapper<?, ?> editMapper
) implements Mapping<A, B> {
  /** A row converting through a two-way mapper. */
  public Via(final Accessor<A, ?> src, final Accessor<B, ?> tgt, final Mapper<?, ?> nested) {
    this(src, tgt, nested, null);
  }

  /** Whether the row names an edit mapper. */
  public boolean edits() {
    return editMapper != null;
  }

  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.receiverClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.receiverClassOf(tgt);
  }

  @Override
  public String sourceField() {
    return LambdaIntrospection.methodNameOf(src);
  }

  @Override
  public String targetField() {
    return LambdaIntrospection.methodNameOf(tgt);
  }
}
