package com.goerdes.correlf.services;

import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.model.TwoFileComparison;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static com.goerdes.correlf.model.RepresentationType.*;
import static com.goerdes.correlf.utils.ByteUtils.unpackBytesToDoubles;
import static com.goerdes.correlf.utils.ByteUtils.unpackBytesToInts;

@Service
@RequiredArgsConstructor
public class FileComparisonService {

    private final MinHashProvider minHashProvider;

    /**
     * Weights for each RepresentationType.
     */
    private final Map<RepresentationType, Double> weights = new EnumMap<>(Map.of(
            ELF_HEADER_VECTOR, 0.0,
            STRING_MINHASH,    0.4,
            SECTION_SIZE_VECTOR, 0.6
    ));


    /**
     * Compares a reference file against a target file and returns
     * a FileComparison for the target.
     *
     * @param referenceFile the original file entity to compare from
     * @param targetFile    the file entity to compare against the reference
     * @return a FileComparison describing the similarity result for the target file
     */
    public TwoFileComparison compareFiles(FileEntity referenceFile, FileEntity targetFile) {
        Map<RepresentationType, Double> comparisons = new HashMap<>();

        double headerSim = getHeaderSim(referenceFile, targetFile);
        comparisons.put(ELF_HEADER_VECTOR, headerSim);

        double stringSim = getStringSim(referenceFile, targetFile);
        comparisons.put(STRING_MINHASH, stringSim);

        double sectionSizeSim = getSectionSizeSim(referenceFile, targetFile);
        comparisons.put(SECTION_SIZE_VECTOR, sectionSizeSim);

        double simScore = comparisons.entrySet().stream()
                .mapToDouble(e -> weights.getOrDefault(e.getKey(), 0.0) * e.getValue())
                .sum();

        return new TwoFileComparison() {{
            setFileName(targetFile.getFilename());
            setSecondFileName(referenceFile.getFilename());
            setComparisonDetails(comparisons);
            setSimilarityScore(simScore);
        }};
    }

    double getSectionSizeSim(FileEntity referenceFile, FileEntity targetFile) {
        return cosineSimilarity(
                unpackBytesToDoubles(
                        referenceFile.findRepresentationByType(SECTION_SIZE_VECTOR).orElseThrow().getData()
                ), unpackBytesToDoubles(
                        targetFile.findRepresentationByType(SECTION_SIZE_VECTOR).orElseThrow().getData()
                )
        );
    }

    double getStringSim(FileEntity referenceFile, FileEntity targetFile) {
        return minHashProvider.get().similarity(
                unpackBytesToInts(
                        referenceFile.findRepresentationByType(STRING_MINHASH).orElseThrow().getData()
                ), unpackBytesToInts(
                        targetFile.findRepresentationByType(STRING_MINHASH).orElseThrow().getData()
                )
        );
    }

    double getHeaderSim(FileEntity referenceFile, FileEntity targetFile) {
        return cosineSimilarity(
                unpackBytesToDoubles(
                        referenceFile.findRepresentationByType(ELF_HEADER_VECTOR).orElseThrow().getData()
                ), unpackBytesToDoubles(
                        targetFile.findRepresentationByType(ELF_HEADER_VECTOR).orElseThrow().getData()
                )
        );
    }

    /**
     * Computes the cosine similarity between two feature vectors:
     * cosine = (A·B) / (||A|| * ||B||)
     *
     * @param a first feature vector
     * @param b second feature vector
     * @return the cosine similarity
     * @throws IllegalArgumentException if vectors differ in length or are zero‐length
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths must match");
        }
        if (a.length == 0) {
            throw new IllegalArgumentException("Vectors must not be empty");
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
