package io.github.eschizoid.telescope.demo.spring.domain.payment;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.demo.spring.legacy.BankTransferEntity;

@Bridge(BankTransferEntity.class)
public record BankTransfer(String iban, String bic) implements Payment {}
