package com.goerdes.correlf.services;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.FileRepo;
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
import java.util.Optional;

import static com.goerdes.correlf.handler.ElfHandler.fromMultipart;
import static com.goerdes.correlf.model.FileComparison.HIGH;

@Service
@RequiredArgsConstructor
public class FileAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FileAnalysisService.class);

    private final FileRepo fileRepo;

    public List<FileComparison> analyze(MultipartFile file) {

        ElfWrapper elfWrapper;
        try {
            elfWrapper = fromMultipart(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Check if the file already exists
        Optional<FileEntity> sha256entity = fileRepo.findBySha256(elfWrapper.getSha256());
        if (sha256entity.isPresent()) {
            return List.of(
                    FileComparison.builder()
                            .similarityScore(1)
                            .fileName(sha256entity.get().getFilename())
                            .similarityRating(HIGH)
                            .build()
            );
        }

        FileEntity fileEntity = ElfHandler.createEntity(elfWrapper);
        fileRepo.save(fileEntity);

        return List.of(new FileComparison());
    }

    public TwoFileComparison compare(MultipartFile file1, MultipartFile file2) {
        return null;
    }
}
