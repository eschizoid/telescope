package io.github.eschizoid.telescope.bridgexpkg.vmapper;

import io.github.eschizoid.telescope.bridgexpkg.vm.VmAddress;
import io.github.eschizoid.telescope.bridgexpkg.vm.VmAddressDto;

/**
 * User-supplied @ViaMapper bridge in its OWN package (cross-package to the generated parent
 * bridge).
 */
public final class VmAddressBridge {

  private VmAddressBridge() {}

  public static VmAddressDto forward(final VmAddress a) {
    return new VmAddressDto(a.line());
  }

  public static VmAddress backward(final VmAddressDto a) {
    return new VmAddress(a.line());
  }

  public static VmAddress patch(final VmAddress base, final VmAddressDto partial) {
    return base;
  }
}
