package io.github.eschizoid.telescope.quarkus.tests.services;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.service;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/** Mapper fields whose rows call services, which the extension registers as bound beans. */
public final class ShopMappers {

  private ShopMappers() {}

  @Bridge
  public static final EditMapper<LineChanges, Line> LINE = edits(
    LineChanges.class,
    Line.class,
    compute(Line::getPrice, service(Catalog::price))
  );

  @Bridge
  public static final ForwardMapper<Line, LineView> VIEW = mapperForward(
    Line.class,
    LineView.class,
    compute(LineView::label, service(Labels::label))
  );

  /** The providers the processor generates for these fields, which an application registers. */
  public static final String[] PROVIDERS = {
    "io.github.eschizoid.telescope.quarkus.tests.services.LineChangesToLineBridgeProvider",
    "io.github.eschizoid.telescope.quarkus.tests.services.LineToLineViewBridgeProvider",
  };
}
