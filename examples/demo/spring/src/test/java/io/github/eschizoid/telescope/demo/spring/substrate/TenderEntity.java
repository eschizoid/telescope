package io.github.eschizoid.telescope.demo.spring.substrate;

/** How a till purchase was paid, as the beans a legacy till system stores. */
public sealed interface TenderEntity permits CardTenderEntity, CashTenderEntity {}
