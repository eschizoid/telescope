package io.github.eschizoid.telescope.codegen.ctorbox;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy;

/** The runtime mapper from {@link CityMappers}'s rows, built where no provider answers for them. */
public final class CityRuntime {

  private CityRuntime() {}

  /** The runtime mapper of {@link CityHolder} to {@link CityRow} through {@link PrivateCity}. */
  public static Mapper<CityHolder, CityRow> mapper() {
    return Telescope.mapper(
      CityHolder.class,
      CityRow.class,
      writeBeans(WriteStrategy.SETTERS),
      to(Telescope.of(CityHolder.class).field(CityHolder::c).field(PrivateCity::getCity), CityRow::city)
    );
  }
}
