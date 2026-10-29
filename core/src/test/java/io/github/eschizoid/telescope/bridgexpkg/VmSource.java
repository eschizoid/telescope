package io.github.eschizoid.telescope.bridgexpkg;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.bridgexpkg.vm.VmAddress;
import io.github.eschizoid.telescope.bridgexpkg.vmapper.VmAddressBridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * A {@code @Bridge} field whose via row names a property type AND a hand-written class in packages
 * other than this source's. The class is named by its qualified name ({@code
 * vmapper.VmAddressBridge}); the cross-package import logic must NOT fabricate a bogus import from
 * it.
 */
public record VmSource(String id, VmAddress address) {
  @Bridge
  public static final Mapper<VmSource, VmTarget> TARGET = mapper(
    VmSource.class,
    VmTarget.class,
    via(VmSource::address, VmTarget::address, VmAddressBridge.class)
  );
}
