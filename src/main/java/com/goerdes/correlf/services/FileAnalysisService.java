package com.goerdes.correlf.services;

import com.goerdes.correlf.components.CoderecParser;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.FileRepo;
import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.model.FileComparison;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final CoderecParser coderecParser;

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

        ElfWrapper elfWrapper = ElfWrapper.of(upload, coderecParser);

        List<FileEntity> stored = fileRepo.findAll();

        if (fileRepo.findBySha256(elfWrapper.getSha256()).stream()
                .map(FileEntity::getFilename)
                .noneMatch(requireNonNull(elfWrapper.getFilename())::equals)
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
        FileEntity e1 = elfHandler.createEntity(ElfWrapper.of(file1, coderecParser));
        FileEntity e2 = elfHandler.createEntity(ElfWrapper.of(file2, coderecParser));

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
     * Imports all ELF binaries contained in the given ZIP archive into the database,
     * but does only one invocation of coderec for the entire batch.
     */
    public void importZipArchive(MultipartFile archive) throws IOException {
        requireNonNull(archive, "Archive must not be null");
        log.info("Importing ZIP archive: {}", archive.getOriginalFilename());

        Path tempDir = Files.createTempDirectory("elf-batch-");
        List<Path> elfPaths = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(archive.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    Path out = tempDir.resolve(entry.getName());
                    Files.createDirectories(out.getParent());
                    Files.write(out, zis.readAllBytes());
                    elfPaths.add(out);
                }
                zis.closeEntry();
            }
        }

        if (elfPaths.isEmpty()) {
            cleanupDir(tempDir);
            return;
        }

        Map<Path, List<CoderecParser.CodeRegion>> batchRegions =
                coderecParser.parseMultiple(elfPaths);

        for (Path elfPath : elfPaths) {
            byte[] bytes = Files.readAllBytes(elfPath);
            MultipartFile mf = new MockMultipartFile(
                    elfPath.getFileName().toString(),
                    elfPath.getFileName().toString(),
                    "application/octet-stream",
                    bytes
            );

            ElfWrapper wrapper = ElfWrapper.of(mf, batchRegions.getOrDefault(elfPath, List.of()));

            if (fileRepo.findBySha256(wrapper.getSha256()).isEmpty()) {
                FileEntity entity = elfHandler.createEntity(wrapper);
                fileRepo.save(entity);
            }
        }

        cleanupDir(tempDir);
    }

    /** Recursively deletes the directory and its contents. */
    private void cleanupDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
        } catch (IOException ignored) { }
    }

}
