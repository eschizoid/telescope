package io.github.telescopeorganization.telescope.declaredbridge;

import static io.github.telescopeorganization.telescope.mapping.Mapping.to;
import static io.github.telescopeorganization.telescope.mapping.WriteHint.writeBean;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import io.github.telescopeorganization.telescope.mapping.WriteHint.WriteStrategy;

/**
 * A {@code @Bridge} field whose rows carry a write hint beside a row naming accessors: the hint
 * names a class rather than accessors, so it is compared with the generated bridge by its kind.
 */
public final class HintedMappers {

  private HintedMappers() {}

  @Bridge
  public static final Mapper<Hinted, HintedBean> HINTED = Telescope.mapper(
    Hinted.class,
    HintedBean.class,
    to(Hinted::name, HintedBean::getLabel),
    writeBean(HintedBean.class, WriteStrategy.SETTERS)
  );
}
