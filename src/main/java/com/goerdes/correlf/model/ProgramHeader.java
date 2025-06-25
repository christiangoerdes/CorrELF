package com.goerdes.correlf.model;

/**
 * Representation of a single ELF Program Header entry.
 *
 * @param type     the segment type
 * @param offset   the offset of the segment within the file image, in bytes
 * @param vaddr    the virtual memory address at which the segment is loaded
 * @param paddr    the physical memory address
 * @param fileSize the number of bytes occupied by the segment in the file
 * @param memSize  the number of bytes occupied by the segment in memory
 * @param flags    the segment permission flags
 * @param align    the alignment constraint of the segment in memory and file, in bytes
 */
public record ProgramHeader(
        String type,
        long offset,
        long vaddr,
        long paddr,
        long fileSize,
        long memSize,
        String flags,
        long align
) {}