package io.github.eschizoid.telescope.codegen.ctorbox;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy;

/**
 * A {@code @Bridge} field whose source path a {@code SETTERS} default writes back through {@link
 * PrivateCity}, whose private no-argument constructor no generated code calls.
 */
public final class CityMappers {

  @Bridge
  public static final Mapper<CityHolder, CityRow> ROW = Telescope.mapper(
    CityHolder.class,
    CityRow.class,
    writeBeans(WriteStrategy.SETTERS),
    to(Telescope.of(CityHolder.class).field(CityHolder::c).field(PrivateCity::getCity), CityRow::city)
  );

  private CityMappers() {}
}
