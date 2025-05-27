package com.goerdes.correlf.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Extension of {@link FileComparison} for comparing two files.
 * <p>
 * Inherits the similarity score and rating logic, and adds the name
 * of the second file in the comparison.
 */
public class TwoFileComparison extends FileComparison{

    /** Name of the second file in the comparison. */
    @Setter
    @Getter
    private String secondFileName;

}
