package com.lumix.template.application.port.in;

/** Inbound port giriş modeli — adapter'lar bu command'ı üretir. */
public record CreateSampleCommand(String name) {

    public CreateSampleCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name zorunlu");
        }
    }
}
