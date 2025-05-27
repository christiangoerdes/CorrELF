package com.goerdes.correlf.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.fornwall.jelf.ElfFile;

/**
 * Container for an ELF file analysis, holding the original filename,
 * the parsed {@link ElfFile}, and the SHA-256 hash of the file content.
 */
@Data
@AllArgsConstructor
public class ElfWrapper {

    /** The original name of the uploaded file. */
    private String filename;

    /** The parsed ELF file representation. */
    private ElfFile elfFile;

    /** The hex-encoded SHA-256 hash of the file content. */
    private final String sha256;

}
