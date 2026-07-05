package com.lumix.template.domain.exception;

import com.lumix.template.domain.model.SampleId;

/** Zaten ACTIVE olan bir Sample yeniden aktifleştirilmeye çalışıldığında (invariant ihlali). */
public class SampleAlreadyActiveException extends RuntimeException {

    private final transient SampleId sampleId;

    public SampleAlreadyActiveException(SampleId sampleId) {
        super("Sample zaten ACTIVE: " + sampleId.value());
        this.sampleId = sampleId;
    }

    public SampleId sampleId() {
        return sampleId;
    }
}
