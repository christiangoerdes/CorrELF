package com.goerdes.correlf.services;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.handler.ElfHandler;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.TwoFileComparison;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.goerdes.correlf.handler.ElfHandler.createEntity;
import static com.goerdes.correlf.handler.ElfHandler.fromMultipart;

/**
 * Service responsible for analyzing uploaded ELF files, comparing them to existing
 * entries, and delegating similarity computation.
 */
@Service
@RequiredArgsConstructor
public class FileAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FileAnalysisService.class);

    private final FileComparisonService comparisonService;
    private final FileRepo fileRepo;

    /**
     * Parse and store the uploaded file (if new), then compare it
     * against all previously stored files.
     *
     * @param upload the ELF file uploaded via API
     * @return list of comparisons against each stored file
     * @throws FileProcessingException if parsing or persistence fails
     */
    public List<FileComparison> analyze(MultipartFile upload) {
        ElfWrapper elfWrapper = toElfWrapper(upload);
        List<FileEntity> stored = fileRepo.findAll();

        if (fileRepo.findBySha256(elfWrapper.getSha256()).stream()
                .map(FileEntity::getFilename)
                .noneMatch(elfWrapper.getFilename()::equals)
        ) {
            fileRepo.save(createEntity(elfWrapper));
        }

        return stored.stream()
                .map(other -> (FileComparison) comparisonService.compareFiles(createEntity(elfWrapper), other))
                .toList();
    }

    /**
     * Compares two uploaded ELF files directly and returns a detailed comparison.
     *
     * @param file1 the first file to compare
     * @param file2 the second file to compare
     * @return a {@link TwoFileComparison} including both filenames, similarity score, and rating
     */
    public TwoFileComparison compare(MultipartFile file1, MultipartFile file2) {
        try {
            FileEntity e1 = createEntity(fromMultipart(file1));
            FileEntity e2 = createEntity(fromMultipart(file2));

            if (e1.getSha256().equals(e2.getSha256())) {
                TwoFileComparison match = getFileMatch(e1);
                match.setSecondFileName(e2.getFilename());
                return match;
            }
            TwoFileComparison fileComparison = comparisonService.compareFiles(e1, e2);
            fileComparison.setSecondFileName(e1.getFilename());
            return fileComparison;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the upload into ElfWrapper, rethrowing IO as a FileProcessingException.
     */
    private ElfWrapper toElfWrapper(MultipartFile upload) {
        try {
            return ElfHandler.fromMultipart(upload);
        } catch (IOException e) {
            String name = upload.getOriginalFilename();
            throw new FileProcessingException("Failed to parse ELF from " + (name != null ? name : "<unnamed>"), e);
        }
    }

    private TwoFileComparison getFileMatch(FileEntity matchingEntity) {
        return new TwoFileComparison() {{
            setFileName(matchingEntity.getFilename());
            setSimilarityScore(1);
        }};
    }
}
