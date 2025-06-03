package com.goerdes.correlf.model;

import com.goerdes.correlf.exception.FileProcessingException;
import lombok.Data;
import net.fornwall.jelf.ElfFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.goerdes.correlf.utils.ByteUtils.computeSha256;

/**
 * Wraps an uploaded ELF binary, preserving its original filename,
 * the parsed {@link ElfFile} instance, and the SHA-256 hash of its contents.
 */
@Data
public class ElfWrapper {

    /** The original filename as provided by the client. */
    private final String filename;

    /** Parsed representation of the ELF binary. */
    private final ElfFile elfFile;

    /** Hex-encoded SHA-256 digest of the file’s bytes. */
    private final String sha256;

    /**
     * Reads the given {@link MultipartFile}, writes it to a temporary file,
     * parses it into an {@link ElfFile}, computes its SHA-256 hash, and
     * populates this wrapper’s fields accordingly.
     *
     * @param file the uploaded ELF file
     * @throws FileProcessingException if any I/O or parsing error occurs, or if the original filename is missing
     */
    public ElfWrapper(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            throw new FileProcessingException("Original filename is missing", null);
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new FileProcessingException("Failed to read uploaded file bytes", e);
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("elf-upload-");
        } catch (IOException e) {
            throw new FileProcessingException("Failed to create temp directory", e);
        }

        Path tempFile = tempDir.resolve(originalName);
        try {
            Files.write(tempFile, content);
            this.filename = originalName;
            this.elfFile = ElfFile.from(tempFile.toFile());
            this.sha256 = computeSha256(content);
        } catch (Exception e) {
            throw new FileProcessingException("Failed to parse ELF from " + originalName, e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {}
        }
    }
}
