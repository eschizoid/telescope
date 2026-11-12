package io.github.eschizoid.telescope.demo.spring.substrate;

import io.github.eschizoid.telescope.annotations.Bridge;

/** A card tender. */
@Bridge(CardTenderEntity.class)
public record CardTender(String number, String holder) implements Tender {}
