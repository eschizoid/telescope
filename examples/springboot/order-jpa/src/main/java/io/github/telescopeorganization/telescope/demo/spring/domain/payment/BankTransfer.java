package io.github.telescopeorganization.telescope.demo.spring.domain.payment;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.demo.spring.legacy.BankTransferEntity;

@Bridge(BankTransferEntity.class)
public record BankTransfer(String iban, String bic) implements Payment {}
