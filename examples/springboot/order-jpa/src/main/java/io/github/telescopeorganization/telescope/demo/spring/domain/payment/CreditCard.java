package io.github.telescopeorganization.telescope.demo.spring.domain.payment;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.demo.spring.legacy.CreditCardEntity;

@Bridge(CreditCardEntity.class)
public record CreditCard(String cardNumber, String holder, int expiryYear) implements Payment {}
