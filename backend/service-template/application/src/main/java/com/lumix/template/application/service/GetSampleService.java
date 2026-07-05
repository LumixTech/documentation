package com.lumix.template.application.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lumix.template.application.port.in.GetSampleUseCase;
import com.lumix.template.application.port.out.SampleRepository;
import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;

@Service
@Transactional(readOnly = true)
public class GetSampleService implements GetSampleUseCase {

    private final SampleRepository repository;

    public GetSampleService(SampleRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Sample> byId(SampleId id) {
        return repository.findById(id);
    }
}
