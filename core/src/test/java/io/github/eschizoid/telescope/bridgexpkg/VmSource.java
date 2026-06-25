package io.github.eschizoid.telescope.bridgexpkg;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.ViaMapper;
import io.github.eschizoid.telescope.bridgexpkg.vm.VmAddress;
import io.github.eschizoid.telescope.bridgexpkg.vmapper.VmAddressBridge;

/**
 * Model-anchored @Bridge with a @ViaMapper field whose type AND mapper class are both in different
 * packages than this source. The @ViaMapper subBridgeName is the user's FQN
 * (vmapper.VmAddressBridge); the cross-package import logic must NOT fabricate a bogus import from
 * it.
 */
@Bridge(value = VmTarget.class, viaMappers = @ViaMapper(field = "address", using = VmAddressBridge.class))
public record VmSource(String id, VmAddress address) {}
