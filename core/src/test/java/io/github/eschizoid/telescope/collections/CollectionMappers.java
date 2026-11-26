package io.github.eschizoid.telescope.collections;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The generated mappers {@link CollectionPathsTest} holds against runtime mappers over the same
 * rows, which it builds itself, from a class no {@code @Bridge} field declares the pairs in.
 */
public final class CollectionMappers {

  private CollectionMappers() {}

  @Bridge
  public static final Mapper<MOrderDto, MOrder> M = Telescope.mapper(MOrderDto.class, MOrder.class);

  @Bridge
  public static final Mapper<MOrder, MOrder> M_COPY = Telescope.mapper(MOrder.class, MOrder.class);

  @Bridge
  public static final Mapper<HOrderDto, HOrder> H = Telescope.mapper(HOrderDto.class, HOrder.class);

  @Bridge
  public static final Mapper<WOrderDto, WOrder> W = Telescope.mapper(WOrderDto.class, WOrder.class);

  @Bridge
  public static final Mapper<ZOrderDto, ZOrder> Z = Telescope.mapper(ZOrderDto.class, ZOrder.class);

  @Bridge
  public static final Mapper<GLineBoxDto, GLineBox> G = Telescope.mapper(GLineBoxDto.class, GLineBox.class);

  @Bridge
  public static final Mapper<ATaggedDto, ATagged> A = Telescope.mapper(ATaggedDto.class, ATagged.class);

  @Bridge
  public static final Mapper<ATagged, ATaggedDto> A_BACK = Telescope.mapper(ATagged.class, ATaggedDto.class);
}
