package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

public record CreditCard(String cardNumber, String holder, int expiryYear) implements Payment {}
