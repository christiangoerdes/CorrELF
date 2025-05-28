package com.goerdes.correlf.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.FetchType.LAZY;

@Entity
@Data
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class FileEntity {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    String filename;

    @Column(nullable = false, unique = true)
    private String sha256;

    @OneToMany(
            mappedBy = "file",
            cascade = ALL,
            orphanRemoval = true,
            fetch = LAZY
    )

    @Builder.Default
    private List<RepresentationEntity> representations = new ArrayList<>();

    public void addRepresentation(RepresentationEntity representation) {
        representation.setFile(this);
        this.representations.add(representation);
    }

}
