package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

public record BankTransfer(String iban, String bic) implements Payment {}
