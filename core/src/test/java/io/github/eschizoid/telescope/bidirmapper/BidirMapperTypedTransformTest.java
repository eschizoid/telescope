package io.github.eschizoid.telescope.bidirmapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bidirectional Mapper with an explicit typed-transform row must apply the conversion before the
 * target setter, instead of passing the source-typed value through to a mismatched setter (which
 * CCEs).
 */
class BidirMapperTypedTransformTest {

  @Test
  @DisplayName("forward applies the explicit String→Integer transform before the Integer setter")
  void forwardAppliesTypedTransform() {
    final var mapper = Telescope.mapperBuilder(GovtIndex.class, IdDetails.class)
      .add(
        Mapping.to(
          GovtIndex::getSorId,
          IdDetails::getSorId,
          s -> s == null ? null : Integer.parseInt(s),
          i -> i == null ? null : String.valueOf(i)
        )
      )
      .build();

    final var out = mapper.forward(new GovtIndex("42"));

    assertEquals(Integer.valueOf(42), out.getSorId());
  }
}
