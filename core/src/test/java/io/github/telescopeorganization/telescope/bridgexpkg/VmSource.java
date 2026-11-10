package io.github.telescopeorganization.telescope.bridgexpkg;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.via;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.bridgexpkg.vm.VmAddress;
import io.github.telescopeorganization.telescope.bridgexpkg.vmapper.VmAddressBridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

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
