package com.goerdes.correlf.services;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.model.TwoFileComparison;
import org.springframework.stereotype.Service;

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
        return new TwoFileComparison() {{
            setFileName(targetFile.getFilename());
            setSecondFileName(referenceFile.getFilename());
            setSimilarityScore(0);
        }};
    }
}
