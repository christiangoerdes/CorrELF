package com.goerdes.correlf.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileRepo extends JpaRepository<FileEntity, UUID> {
    List<FileEntity> findBySha256(String sha256);
}
