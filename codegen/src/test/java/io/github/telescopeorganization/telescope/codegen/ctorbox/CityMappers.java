package io.github.telescopeorganization.telescope.codegen.ctorbox;

import static io.github.telescopeorganization.telescope.mapping.Mapping.to;
import static io.github.telescopeorganization.telescope.mapping.WriteHint.writeBeans;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import io.github.telescopeorganization.telescope.mapping.WriteHint.WriteStrategy;

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
