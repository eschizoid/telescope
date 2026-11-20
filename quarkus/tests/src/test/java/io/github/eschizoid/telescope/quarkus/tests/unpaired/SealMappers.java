package io.github.eschizoid.telescope.quarkus.tests.unpaired;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** The {@code Mapper} bean for {@code Seal -> Unrelated}, whose components share no name. */
@ApplicationScoped
public class SealMappers {

  @Produces
  Mapper<Seal, Unrelated> unrelated() {
    return Telescope.mapper(
      Seal.class,
      Unrelated.class,
      to(Seal::mark, Unrelated::zzz),
      to(Seal::size, Unrelated::yyy)
    );
  }
}
