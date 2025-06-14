package com.goerdes.correlf.services;

import com.goerdes.correlf.components.Coderec;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.ElfWrapperFactory;
import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.model.FileComparison;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;

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
    private final ElfHandler elfHandler;
    private final ElfWrapperFactory factory;
    private final Coderec coderec;

    @Value("${coderec.enabled}")
    private boolean coderecEnabled;

    /**
     * Parse and store the uploaded file (if new), then compare it
     * against all previously stored files.
     *
     * @param upload the ELF file uploaded via API
     * @return list of comparisons against each stored file
     * @throws FileProcessingException if parsing or persistence fails
     */
    @Transactional
    public List<FileComparison> analyze(MultipartFile upload) {
        log.info("Analyzing: {}", upload.getOriginalFilename());

        ElfWrapper elfWrapper = factory.create(upload);

        List<FileEntity> stored = fileRepo.findAll();

        if (fileRepo.findBySha256(elfWrapper.sha256()).stream()
                .map(FileEntity::getFilename)
                .noneMatch(requireNonNull(elfWrapper.filename())::equals)
        ) {
            fileRepo.save(elfHandler.createEntity(elfWrapper));
        }

        return stored.stream()
                .map(other -> comparisonService.compareFiles(elfHandler.createEntity(elfWrapper), other))
                .toList();
    }

    /**
     * Compares two uploaded ELF files directly and returns a detailed comparison.
     *
     * @param file1 the first file to compare
     * @param file2 the second file to compare
     * @return a {@link FileComparison} including both filenames, similarity score, and rating
     */
    @Transactional
    public FileComparison compare(MultipartFile file1, MultipartFile file2) {
        FileEntity e1 = elfHandler.createEntity(factory.create(file1));
        FileEntity e2 = elfHandler.createEntity(factory.create(file2));

        if (e1.getSha256().equals(e2.getSha256())) {
            return new FileComparison() {{
                setFileName(e1.getFilename());
                setSecondFileName(e2.getFilename());
                setSimilarityScore(1);
            }};
        }
        return comparisonService.compareFiles(e1, e2);
    }

    /**
     * Imports all ELF binaries contained in the given ZIP archive into the database.
     * <p>
     * Each non-directory entry is read into memory, wrapped in a
     * {@code ByteArrayMultipartFile}, and persisted via {@link #addToDB(MultipartFile)}.
     * Errors for individual entries are logged but do not interrupt processing
     * of the remaining entries.
     *
     * @param archive the ZIP file containing one or more ELF binaries
     * @throws IOException if an I/O error occurs while reading the archive
     */
    public void importZipArchive(MultipartFile archive) throws IOException {
        requireNonNull(archive, "Archive must not be null");
        log.info("Importing ZIP archive: {}", archive.getOriginalFilename());

        List<MockMultipartFile> files = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(archive.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.add(new MockMultipartFile(
                            entry.getName(),
                            entry.getName(),
                            "application/octet-stream",
                            zis.readAllBytes()
                    ));
                }
                zis.closeEntry();
            }
        }

        int total = files.size();
        int nextLogThreshold = 5;
        log.info("Processing {} files", total);
        for (int i = 0; i < total; i++) {
            MultipartFile file = files.get(i);
            int percent = (i * 100) / total;
            if (percent >= nextLogThreshold) {
                log.info("  → {}% done. ({} of {} files)", percent, i, total);
                nextLogThreshold += 5;
            }
            try {
                addToDB(file);
            } catch (FileProcessingException e) {
                log.error("Failed to process '{}': {}", file.getOriginalFilename(), e.getMessage());
            }
        }
    }

    /**
     * Parses the given uploaded ELF file, constructs its corresponding
     * FileEntity (including SHA-256 and extracted representations), and
     * persists it to the database.
     *
     * @param file the ELF file received as a MultipartFile
     * @throws FileProcessingException if parsing or representation extraction fails
     */
    public void addToDB(MultipartFile file) {
        fileRepo.save(elfHandler.createEntity(factory.create(file)));
    }

}
