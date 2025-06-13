package com.goerdes.correlf.model;

import com.goerdes.correlf.components.Coderec.CodeRegion;
import net.fornwall.jelf.ElfFile;

import java.util.List;

/**
 * Encapsulates an uploaded ELF binary:
 * <ul>
 *   <li>Original filename as provided by the client</li>
 *   <li>Parsed {@link ElfFile} for internal metadata and structure</li>
 *   <li>SHA-256 digest of the file contents</li>
 *   <li>File size in bytes</li>
 *   <li>List of {@link CodeRegion code regions}</li>
 * </ul>
 */
public record ElfWrapper(String filename, ElfFile elfFile, String sha256, long size, List<CodeRegion> codeRegions) {}
