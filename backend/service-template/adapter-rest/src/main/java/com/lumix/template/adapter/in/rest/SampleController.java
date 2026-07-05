package com.lumix.template.adapter.in.rest;

import com.lumix.template.adapter.in.rest.dto.CreateSampleRequest;
import com.lumix.template.adapter.in.rest.dto.SampleResponse;
import com.lumix.template.adapter.in.rest.mapper.SampleRestMapper;
import com.lumix.template.application.port.in.CreateSampleCommand;
import com.lumix.template.application.port.in.CreateSampleUseCase;
import com.lumix.template.application.port.in.GetSampleUseCase;
import com.lumix.template.domain.model.SampleId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inbound REST adapter — yalnızca inbound port'ları (use case) çağırır. */
@RestController
@RequestMapping("/api/v1/samples")
public class SampleController {

    private final CreateSampleUseCase createSampleUseCase;
    private final GetSampleUseCase getSampleUseCase;

    public SampleController(CreateSampleUseCase createSampleUseCase, GetSampleUseCase getSampleUseCase) {
        this.createSampleUseCase = createSampleUseCase;
        this.getSampleUseCase = getSampleUseCase;
    }

    @PostMapping
    public ResponseEntity<SampleResponse> create(@RequestBody @Valid CreateSampleRequest request) {
        SampleId id = createSampleUseCase.create(new CreateSampleCommand(request.name()));
        return ResponseEntity.created(URI.create("/api/v1/samples/" + id.value()))
                .body(new SampleResponse(id.value(), request.name(), "DRAFT"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SampleResponse> getById(@PathVariable UUID id) {
        return getSampleUseCase
                .byId(new SampleId(id))
                .map(SampleRestMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
