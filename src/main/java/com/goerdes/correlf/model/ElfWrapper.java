package com.goerdes.correlf.model;

import com.goerdes.correlf.components.CoderecParser;
import com.goerdes.correlf.components.CoderecParser.CodeRegion;
import com.goerdes.correlf.exception.FileProcessingException;
import lombok.Data;
import net.fornwall.jelf.ElfFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.goerdes.correlf.utils.ByteUtils.computeSha256;

/**
 * Encapsulates an uploaded ELF binary:
 * <ul>
 *   <li>Original filename as provided by the client</li>
 *   <li>Parsed {@link ElfFile} for internal metadata and structure</li>
 *   <li>SHA-256 digest of the file contents</li>
 *   <li>File size in bytes</li>
 *   <li>List of {@link CodeRegion code regions} as identified by coderec</li>
 * </ul>
 */
@Data
public class ElfWrapper {

    /**
     * The original filename as provided by the client.
     */
    private final String filename;

    /**
     * Parsed representation of the ELF binary.
     */
    private final ElfFile elfFile;

    /**
     * Hex-encoded SHA-256 digest of the file’s bytes.
     */
    private final String sha256;

    /**
     * The size of the file.
     */
    private final long size;

    /**
     * Regions identified by coderec in the binary.
     * Each {@link CodeRegion} records
     * an inclusive start offset, exclusive end offset,
     * the region length, and a tag indicating the type.
     * Used to construct a CODE_REGION_VECTOR representation.
     */
    private final List<CodeRegion> codeRegions;

    private ElfWrapper(String filename, ElfFile elfFile, String sha256, long size, List<CodeRegion> codeRegions) {
        this.filename = filename;
        this.elfFile  = elfFile;
        this.sha256   = sha256;
        this.size     = size;
        this.codeRegions = codeRegions;
    }

    /**
     * Reads the given {@link MultipartFile}, writes it to a temporary file,
     * parses it into an {@link ElfFile}, computes its SHA-256 hash,
     * invokes the provided {@link CoderecParser} to extract code regions,
     * and populates this wrapper’s fields accordingly.
     *
     * @param file   the uploaded ELF file
     * @param parser the CoderecParser used to analyze code regions in the binary
     * @throws FileProcessingException if any I/O, parsing, or external analysis error occurs,
     *                                 or if the original filename is missing
     */
    public static ElfWrapper of(MultipartFile file, CoderecParser parser) {
        try {
            String filename = file.getOriginalFilename();

            byte[] content = file.getBytes();
            if (filename == null) {
                throw new FileProcessingException("Missing original filename", null);
            }

            Path tempFile = Files.createTempDirectory("elf-").resolve(filename);
            Files.write(tempFile, content);

            List<CodeRegion> codeRegions = parser.parseSingle(tempFile);

            Path tempDir = tempFile.getParent();
            Files.deleteIfExists(tempFile);

            // recursively delete everything under tempDir
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {}
            return of(file, codeRegions);
        } catch (Exception e) {
            throw new FileProcessingException("Failed to wrap ELF: " + e.getMessage(), e);
        }
    }

    public static ElfWrapper of(MultipartFile file, List<CodeRegion> codeRegions) {
        String filename = file.getOriginalFilename();

        if (filename == null) {
            throw new FileProcessingException("Missing original filename", null);
        }

        byte[] content = null;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new FileProcessingException("Failed reading file content", null);
        }

        return new ElfWrapper(filename, ElfFile.from(content), computeSha256(content), file.getSize(), codeRegions);
    }
}
