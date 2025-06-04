package com.goerdes.correlf.model;

/**
 * Defines the various representation types that can be extracted from a file
 * and stored in the database. Each enum constant corresponds to a specific
 * feature extraction or representation format.
 */
public enum RepresentationType {
    /** A fixed‐length numeric vector encoding the key fields of an ELF header. */
    ELF_HEADER_VECTOR,

    /** A MinHash signature (packed as bytes) representing the ELF’s string set. */
    STRING_MINHASH,

    /**
     * A fixed‐length vector of normalized sizes (as a fraction of total file size)
     * for key ELF sections: .text, .rodata, .data, .bss, .symtab, and .shstrtab.
     */
    SECTION_SIZE_VECTOR
}
