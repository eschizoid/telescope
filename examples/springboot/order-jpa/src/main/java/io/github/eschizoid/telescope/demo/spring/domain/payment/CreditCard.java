package io.github.eschizoid.telescope.demo.spring.domain.payment;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.demo.spring.legacy.CreditCardEntity;

@Bridge(CreditCardEntity.class)
public record CreditCard(String cardNumber, String holder, int expiryYear) implements Payment {}
