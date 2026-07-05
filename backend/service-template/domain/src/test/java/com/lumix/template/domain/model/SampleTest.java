package com.lumix.template.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumix.template.domain.exception.SampleAlreadyActiveException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SampleTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void create_draft_ve_created_event_uretir() {
        Sample sample = Sample.create(SampleId.newId(), "Alpha", FIXED);

        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(sample.name()).isEqualTo("Alpha");
        assertThat(sample.domainEvents()).hasSize(1);
    }

    @Test
    void bos_isim_reddedilir() {
        assertThatThrownBy(() -> Sample.create(SampleId.newId(), "  ", FIXED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ikinci_activate_invariant_ihlali_atar() {
        Sample sample = Sample.create(SampleId.newId(), "Alpha", FIXED);
        sample.activate();

        assertThatThrownBy(sample::activate).isInstanceOf(SampleAlreadyActiveException.class);
    }
}
