package io.github.eschizoid.telescope.bridgexpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.bridgexpkg.vm.VmAddress;
import io.github.eschizoid.telescope.bridgexpkg.vm.VmAddressDto;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @ViaMapper} class is arbitrary user code: unlike the auto-derived sub-bridges, whose
 * generated {@code forward}/{@code backward} open with a null guard, nothing guarantees the user's
 * methods tolerate null — the fixture bridge dereferences its argument unconditionally. The
 * generated parent bridge therefore null-gates the call, matching the runtime {@code via} row's
 * null-in/null-out contract. Each test asserts the absolute expected value AND codegen/runtime
 * equality, so two implementations agreeing on a wrong answer cannot pass.
 */
class ViaMapperNullGuardTest {

  private static io.github.eschizoid.telescope.conversion.Mapper<VmSource, VmTarget> runtime() {
    final var sub = Telescope.mapper(VmAddress.class, VmAddressDto.class);
    return Telescope.mapper(VmSource.class, VmTarget.class, Mapping.via(VmSource::address, VmTarget::address, sub));
  }

  @Test
  @DisplayName("forward null-propagates a null @ViaMapper field instead of handing null to user code")
  void forwardNullPropagatesTheViaField() {
    final var source = new VmSource("i", null);

    final var codegen = VmSourceBridge.forward(source);

    assertEquals(new VmTarget("i", null), codegen);
    assertEquals(runtime().forward(source), codegen);
  }

  @Test
  @DisplayName("backward null-propagates a null @ViaMapper field instead of handing null to user code")
  void backwardNullPropagatesTheViaField() {
    final var target = new VmTarget("i", null);

    final var codegen = VmSourceBridge.backward(target);

    assertEquals(new VmSource("i", null), codegen);
    assertEquals(runtime().backward(target), codegen);
  }

  @Test
  @DisplayName("a present @ViaMapper value still converts through the user bridge")
  void presentValueStillConvertsThroughTheUserBridge() {
    final var source = new VmSource("i", new VmAddress("l1"));

    final var codegen = VmSourceBridge.forward(source);

    assertEquals(new VmTarget("i", new VmAddressDto("l1")), codegen);
    assertEquals(runtime().forward(source), codegen);
  }
}
