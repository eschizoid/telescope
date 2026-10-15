package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;

/** A case declaring bridges to two targets, the one to a case of the sealed target renaming. */
@Bridge(value = SfADto.class, renames = @Rename(source = "x", target = "width"))
@Bridge(SfAOther.class)
public record SfA(int x) implements SfShape {}
