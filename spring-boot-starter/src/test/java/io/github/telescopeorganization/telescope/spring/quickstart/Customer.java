package io.github.telescopeorganization.telescope.spring.quickstart;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(CustomerDto.class)
public record Customer(String name, Contact contact) {}
