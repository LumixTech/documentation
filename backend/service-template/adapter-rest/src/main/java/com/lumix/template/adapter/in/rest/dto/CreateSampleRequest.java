package com.lumix.template.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** HTTP input DTO. Adapter katmanı input validation'ı (bkz. 04-validation-strategy). */
public record CreateSampleRequest(@NotBlank @Size(max = 200) String name) {}
