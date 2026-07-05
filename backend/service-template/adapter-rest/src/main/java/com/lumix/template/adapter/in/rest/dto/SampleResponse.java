package com.lumix.template.adapter.in.rest.dto;

import java.util.UUID;

/** HTTP output DTO. */
public record SampleResponse(UUID id, String name, String status) {}
