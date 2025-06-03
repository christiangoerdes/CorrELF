package com.goerdes.correlf.db;

import com.goerdes.correlf.model.RepresentationType;
import jakarta.persistence.*;
import lombok.*;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "file")
public class RepresentationEntity {

    @Id
    @GeneratedValue
    private Long id;

    @Enumerated(STRING)
    @Column(nullable = false)
    private RepresentationType type;

    @Lob
    @Column(nullable = false)
    private byte @NonNull [] data;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

}
