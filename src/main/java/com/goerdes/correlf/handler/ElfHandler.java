package com.goerdes.correlf.handler;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.model.ElfWrapper;
import net.fornwall.jelf.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ElfHandler {

    /**
     * Reads the provided MultipartFile, computes its SHA-256 hash,
     * writes it to a temporary file named as the original upload,
     * parses it into an ElfFile, and returns an ElfWrapper.
     *
     * @param file the uploaded MultipartFile
     * @return an ElfWrapper containing the original filename, parsed ElfFile, and SHA-256 hash
     * @throws IOException if an I/O error occurs during file operations
     */
    public static ElfWrapper fromMultipart(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            throw new FileProcessingException("Original filename is missing", null);
        }

        byte[] content = file.getBytes();

        Path tempDir  = Files.createTempDirectory("elf-upload-");
        Path tempFile = tempDir.resolve(originalName);
        try {
            Files.write(tempFile, content);
            return new ElfWrapper(
                    originalName,
                    ElfFile.from(tempFile.toFile()),
                    computeSha256(content)
            );
        } catch (Exception e) {
            throw new FileProcessingException("Failed to parse ELF from " + originalName, e);
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * Computes the SHA-256 digest of the given byte array and returns
     * it as a lowercase hexadecimal string.
     *
     * @param data the input bytes to hash
     * @return the hex-encoded SHA-256 hash
     * @throws FileProcessingException if SHA-256 algorithm is unavailable
     */
    private static String computeSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new FileProcessingException("SHA-256 algorithm not available", e);
        }
    }

    public static FileEntity createEntity(ElfWrapper elfWrapper) throws FileProcessingException {
        try {
            ElfFile elf = elfWrapper.getElfFile();

            // ELF-Header vollständig
            System.out.println("ELF Header:");
            System.out.printf("  Class:       %s-bit%n", elf.ei_class == ElfFile.CLASS_32 ? "32" : "64");
            System.out.printf("  Encoding:    %s%n",   elf.ei_data  == ElfFile.DATA_LSB  ? "LSB" : "MSB");
            System.out.printf("  Version:     %d%n",   elf.ei_version);
            System.out.printf("  OS/ABI:      %d%n",   elf.ei_osabi);
            System.out.printf("  ABI-Version: %d%n",   elf.es_abiversion);
            System.out.printf("  Type:        %d%n",   elf.e_type);
            System.out.printf("  Machine:     %d%n",   elf.e_machine);
            System.out.printf("  Entry:       0x%x%n", elf.e_entry);
            System.out.printf("  PhOff:       0x%x%n", elf.e_phoff);
            System.out.printf("  ShOff:       0x%x%n", elf.e_shoff);
            System.out.printf("  Flags:       0x%x%n", elf.e_flags);
            System.out.printf("  EHSize:      %d%n",   elf.e_ehsize);
            System.out.printf("  PhEntrySize: %d, PhCount: %d%n", elf.e_phentsize, elf.e_phnum);
            System.out.printf("  ShEntrySize: %d, ShCount: %d%n", elf.e_shentsize, elf.e_shnum);
            System.out.printf("  ShStrIdx:    %d%n",   elf.e_shstrndx);


            // Program-Headers
            System.out.println("Program Headers:");
            for (int i = 0; i < elf.e_phnum; i++) {
                ElfSegment ph = elf.getProgramHeader(i);
                System.out.printf(
                        "  [%2d] Type=0x%x Off=0x%x VAddr=0x%x PAddr=0x%x%n" +
                                "       FileSz=0x%x MemSz=0x%x Flags=0x%x Align=0x%x%n",
                        i, ph.p_type, ph.p_offset, ph.p_vaddr, ph.p_paddr,
                        ph.p_filesz, ph.p_memsz, ph.p_flags, ph.p_align
                );
            }

            // Section-Headers + String-Tabellen
            System.out.println("\nSection Headers:");
            for (int i = 0; i < elf.e_shnum; i++) {
                ElfSection sec = elf.getSection(i);
                ElfSectionHeader sh = sec.header;
                System.out.printf(
                        "  [%2d] %-15s Type=%d Flags=0x%x Addr=0x%x Off=0x%x Size=0x%x%n" +
                                "       Link=%d Info=%d Align=%d EntSize=%d%n",
                        i, sh.getName(), sh.sh_type, sh.sh_flags,
                        sh.sh_addr, sh.sh_offset, sh.sh_size,
                        sh.sh_link, sh.sh_info, sh.sh_addralign, sh.sh_entsize
                );

                if (sh.sh_type == ElfSectionHeader.SHT_STRTAB) {
                    // getData liefert byte[]
                    ByteBuffer buf = ByteBuffer.wrap(sec.getData());
                    printStrings(buf);
                }
            }

            // Symbol-Tabellen (.symtab + .dynsym)
            System.out.println("\nSymbol Tables:");
            ElfSymbolTableSection symtab = elf.getSymbolTableSection();
            ElfSymbolTableSection dynsym = elf.getDynamicSymbolTableSection();
            for (ElfSymbolTableSection table : new ElfSymbolTableSection[]{symtab, dynsym}) {
                if (table == null) continue;
                System.out.println("  " + table.header.getName()
                        + " (" + table.symbols.length + " entries):");
                for (ElfSymbol sym : table.symbols) {
                    System.out.printf(
                            "    %-20s Val=0x%x Size=%d Info=0x%x Other=0x%x Shndx=%d%n",
                            sym.getName(), sym.st_value, sym.st_size,
                            sym.st_info, sym.st_other, sym.st_shndx
                    );
                }
            }

            // Dynamic Section
            ElfDynamicSection dyn = elf.getDynamicSection();
            if (dyn != null) {
                System.out.println("\nDynamic Section Entries:");
                for (ElfDynamicSection.ElfDynamicStructure entry : dyn.entries) {
                    System.out.printf(
                            "  Tag=0x%x Val/Ptr=0x%x%n",
                            entry.d_tag, entry.d_val_or_ptr
                    );
                }
                // Zusätzliche Infos
                System.out.println("Needed Libraries: " + dyn.getNeededLibraries());
                String runpath = dyn.getRunPath();
                if (runpath != null) System.out.println("RunPath: " + runpath);
            }

        } catch (Exception e) {
            throw new FileProcessingException("ELF-Parsing fehlgeschlagen", e);
        }

        return FileEntity.builder()
                .filename(elfWrapper.getFilename())
                .sha256(elfWrapper.getSha256())
                .build();
    }

    private static void printStrings(ByteBuffer buf) {
        System.out.println("    String-Table:");
        buf.rewind();
        while (buf.hasRemaining()) {
            StringBuilder sb = new StringBuilder();
            byte b;
            while (buf.hasRemaining() && (b = buf.get()) != 0) {
                sb.append((char) b);
            }
            if (sb.length() > 0) {
                System.out.println("      " + sb);
            }
        }
    }
}
