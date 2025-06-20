package com.goerdes.correlf.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRepo extends JpaRepository<FileEntity, UUID> {

    Optional<FileEntity> findById(Long id);

    List<FileEntity> findBySha256(String sha256);

    Optional<FileEntity> findBySha256AndFilename(String sha256, String filename);
}
