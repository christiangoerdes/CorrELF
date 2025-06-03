package com.goerdes.correlf.model;

import lombok.*;

/**
 * Represents the result of comparing two files, providing a similarity score
 * (0.0–1.0) and a derived rating category based on defined thresholds.
 */
@Getter
@RequiredArgsConstructor
@Builder
@AllArgsConstructor
public class FileComparison {

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    /** Minimum score (inclusive) to qualify as a “high” similarity. */
    private static final double HIGH_THRESHOLD = 0.7; //TODO Adjust value

    /** Maximum score (inclusive) to qualify as a “low” similarity. */
    private static final double LOW_THRESHOLD  = 0.3; //TODO Adjust value

    /** Computed similarity score between two files (0.0–1.0). */
    private double similarityScore;

    /** Category corresponding to the similarityScore: HIGH, MEDIUM or LOW. */
    private String similarityRating;

    /** Name of the file being compared. */
    @Setter
    private String fileName;

    /**
     * Sets the similarity score and immediately re-computes its rating.
     *
     * @param similarityScore a value between 0.0 and 1.0
     */
    public void setSimilarityScore(double similarityScore) {
        this.similarityScore = similarityScore;
        setSimilarityRating(similarityScore);
    }

    /**
     * Determines and assigns the similarityRating based on the current similarityScore.
     *
     * @param score a value between 0.0 and 1.0
     */
    private void setSimilarityRating(double score) {
        this.similarityRating = score >= HIGH_THRESHOLD ? HIGH : score <= LOW_THRESHOLD ? LOW : MEDIUM;
    }

    //TODO add detailed information for representation comparison

}
