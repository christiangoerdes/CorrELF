package com.goerdes.correlf.services;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.model.TwoFileComparison;
import org.springframework.stereotype.Service;

import static com.goerdes.correlf.model.RepresentationType.ELF_HEADER_VECTOR;
import static com.goerdes.correlf.utils.ByteUtils.unpackBytesToDoubles;

@Service
public class FileComparisonService {

    /**
     * Compares a reference file against a target file and returns
     * a FileComparison for the target.
     *
     * @param referenceFile the original file entity to compare from
     * @param targetFile the file entity to compare against the reference
     * @return a FileComparison describing the similarity result for the target file
     */
    public TwoFileComparison compareFiles(FileEntity referenceFile, FileEntity targetFile) {

        double[] referenceFileHeader = unpackBytesToDoubles(
                referenceFile.findRepresentationByType(ELF_HEADER_VECTOR).orElseThrow().getData()
        );
        double[] targetFileHeader = unpackBytesToDoubles(
                targetFile.findRepresentationByType(ELF_HEADER_VECTOR).orElseThrow().getData()
        );

        double similarity = cosineSimilarity(referenceFileHeader, targetFileHeader);

        return new TwoFileComparison() {{
            setFileName(targetFile.getFilename());
            setSecondFileName(referenceFile.getFilename());
            setSimilarityScore(similarity);
        }};
    }

    /**
     * Computes the cosine similarity between two feature vectors:
     *   cosine = (A·B) / (||A|| * ||B||)
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
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
