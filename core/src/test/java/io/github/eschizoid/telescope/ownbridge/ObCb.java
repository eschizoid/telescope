package io.github.eschizoid.telescope.ownbridge;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** The other side, whose own bridge upper-cases its label. */
public class ObCb {

  @Bridge
  public static final Mapper<ObCb, ObCbDto> DTO = mapper(
    ObCb.class,
    ObCbDto.class,
    to(ObCb::getLabel, ObCbDto::getLabel, ObUpperFn.class)
  );

  private String label;
  private ObCa a;

  public ObCb() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public ObCa getA() {
    return a;
  }

  public void setA(final ObCa a) {
    this.a = a;
  }
}
