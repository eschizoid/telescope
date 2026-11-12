package io.github.eschizoid.telescope.demo.spring.substrate;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * How a till purchase was paid, as records. {@code @Bridge} on the sealed root generates {@code
 * TenderBridge.BRIDGE}, which dispatches each case to the bridge its own {@code @Bridge} generates.
 */
@Bridge(TenderEntity.class)
public sealed interface Tender permits CardTender, CashTender {}
