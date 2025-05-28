package com.goerdes.correlf.db;

import com.goerdes.correlf.model.RepresentationType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    @Column(nullable = false)
    private String sha256;

    @OneToMany(
            mappedBy = "file",
            cascade = ALL,
            orphanRemoval = true,
            fetch = LAZY
    )

    @Builder.Default
    private List<RepresentationEntity> representations = new ArrayList<>();

    /**
     * Finds the first RepresentationEntity of the given type.
     *
     * @param type the representation type to look up
     * @return an Optional containing the first match, or empty if none found
     */
    public Optional<RepresentationEntity> findRepresentationByType(RepresentationType type) {
        return representations.stream()
                .filter(rep -> rep.getType() == type)
                .findFirst();
    }

    public void addRepresentation(RepresentationEntity representation) {
        representation.setFile(this);
        this.representations.add(representation);
    }

}
