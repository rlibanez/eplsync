package com.rlibanez.eplsync.repository;

import com.rlibanez.eplsync.model.CatalogMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogMetadataRepository extends JpaRepository<CatalogMetadata, Long> {}
