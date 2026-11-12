package io.github.eschizoid.telescope.demo.spring.substrate;

import io.github.eschizoid.telescope.annotations.Bridge;

/** A cash tender. */
@Bridge(CashTenderEntity.class)
public record CashTender(String currency) implements Tender {}
