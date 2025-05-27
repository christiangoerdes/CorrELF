package com.goerdes.correlf.services;

import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.handler.FileHandler;
import com.goerdes.correlf.model.FileComparison;
import com.goerdes.correlf.model.TwoFileComparison;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Service for analyzing uploaded files by delegating to the appropriate FileHandler.
 * <p>
 * Creates a temporary file for processing, selects a handler, invokes it, and
 * ensures cleanup of the temporary resource.
 */
@Service
@RequiredArgsConstructor
public class FileAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FileAnalysisService.class);

    private final FileHandlerRegistry registry;

    /**
     * Analyze the given multipart file.
     * <ol>
     *   <li>Creates a temp file.</li>
     *   <li>Transfers upload contents to it.</li>
     *   <li>Locates and invokes the supporting FileHandler.</li>
     *   <li>Cleans up the temp file.</li>
     * </ol>
     *
     * @param file the uploaded file to analyze
     * @throws FileProcessingException if no handler is found or processing fails
     */
    public List<FileComparison> analyze(MultipartFile file) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("analysis-", ".tmp");
            file.transferTo(tempFile);

            FileHandler handler = registry.getHandler(tempFile);
            log.info("Using handler: {}", handler.getClass().getName());
            handler.handle(tempFile);

        } catch (FileProcessingException e) {
            throw e;
        } catch (Exception e) {
            String name = file.getOriginalFilename();
            throw new FileProcessingException(
                    "Analysis failed for file: " + (name != null ? name : "<unknown>"), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ioe) {
                    log.warn("Failed to delete temp file {}", tempFile, ioe);
                }
            }
        }
        return null;
    }

    public TwoFileComparison compare(MultipartFile file1, MultipartFile file2) {
        return null;
    }
}
