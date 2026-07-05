package com.lumix.template.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository (teknik). Domain, bu tipi görmez. */
public interface SampleJpaRepository extends JpaRepository<SampleJpaEntity, UUID> {}
