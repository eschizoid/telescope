package io.github.telescopeorganization.telescope.codegen.lombok.fixtures;

import static io.github.telescopeorganization.telescope.Telescope.mapperForward;
import static io.github.telescopeorganization.telescope.mapping.Mapping.toOneWay;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.ForwardMapper;

/**
 * Fixture: the source of a bridge into {@link OrderedLombokTarget}, converting both of its
 * components forward through {@link OrderedLog}.
 */
public record OrderedLombokSource(String firstLo, String secondLo) {
  @Bridge
  public static final ForwardMapper<OrderedLombokSource, OrderedLombokTarget> TARGET = mapperForward(
    OrderedLombokSource.class,
    OrderedLombokTarget.class,
    toOneWay(OrderedLombokSource::firstLo, OrderedLombokTarget::getFirstLo, OrderedLog.INSTANCE::forward),
    toOneWay(OrderedLombokSource::secondLo, OrderedLombokTarget::getSecondLo, OrderedLog.INSTANCE::forward)
  );
}
