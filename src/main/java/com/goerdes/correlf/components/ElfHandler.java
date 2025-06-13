package com.goerdes.correlf.components;

import com.goerdes.correlf.db.FileEntity;
import com.goerdes.correlf.db.RepresentationEntity;
import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.model.RepresentationType;
import lombok.RequiredArgsConstructor;
import net.fornwall.jelf.*;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;

import static com.goerdes.correlf.components.MinHashProvider.MINHASH_DICT_SIZE;
import static com.goerdes.correlf.model.RepresentationType.*;
import static com.goerdes.correlf.utils.ByteUtils.*;
import static java.util.AbstractMap.SimpleEntry;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.IntStream.range;
import static net.fornwall.jelf.ElfSectionHeader.SHT_STRTAB;

@RequiredArgsConstructor
@Component
public class ElfHandler {

    private final MinHashProvider minHashProvider;

    /**
     * Creates a FileEntity from the given ELF wrapper by extracting
     * representations (header, sections, symbols, strings…).
     *
     * @param elfWrapper parsed ELF plus filename & SHA-256
     * @return FileEntity with filename, sha256 and representations
     */
    public FileEntity createEntity(ElfWrapper elfWrapper) throws FileProcessingException {
        FileEntity entity = FileEntity.builder()
                .filename(elfWrapper.filename())
                .sha256(elfWrapper.sha256())
                .parsingSuccessful(elfWrapper.parsingSuccessful())
                .build();

        if (elfWrapper.parsingSuccessful()) {
            ElfFile elfFile = elfWrapper.elfFile();

            entity.addRepresentation(new RepresentationEntity() {{
                setType(ELF_HEADER_VECTOR);
                setData(packDoublesToBytes(extractHeaderVector(elfFile)));
                setFile(entity);
            }});


            entity.addRepresentation(new RepresentationEntity() {{
                setType(STRING_MINHASH);
                setData(packIntsToBytes(minHashProvider.get().signature(mapStringsToTokens(extractStrings(elfFile)))));
                setFile(entity);
            }});

            entity.addRepresentation(new RepresentationEntity() {{
                setType(SECTION_SIZE_VECTOR);
                setData(packDoublesToBytes(buildSectionSizeVector(elfFile, elfWrapper.size())));
                setFile(entity);
            }});
        }

        entity.addRepresentation(new RepresentationEntity(){{
            setType(RepresentationType.CODE_REGION_LIST);
            setData(serializeCodeRegions(elfWrapper.codeRegions()));  // hier wird Deine Liste serialized
            setFile(entity);
        }});

        // debugPrint(elfFile);

        return entity;
    }

    /**
     * Returns a length-6 vector of normalized section sizes for the ELF:
     * [".text", ".rodata", ".data", ".bss", ".symtab", ".shstrtab"].
     * Missing sections yield 0.0.
     *
     * @param elf the parsed ELF file
     * @return a double[6] of (sectionSize / totalSize)
     * @throws ElfException if any section cannot be read
     */
    public static double[] buildSectionSizeVector(ElfFile elf, long size) throws ElfException {

        Map<String, Integer> idxMap = Map.of(
                ".text",0,  // executable instructions
                ".rodata",  1,  // read-only data (constants, literals)
                ".data",    2,  // initialized writable data
                ".bss",     3,  // uninitialized writable data (zero-initialized)
                ".symtab",  4,  // symbol table
                ".shstrtab",    5   // section-header string table
        );

        Map<String, Long> sectionSizeMap = IntStream.range(0, elf.e_shnum)
                .mapToObj(elf::getSection)
                .map(section -> (section.header.getName() == null)
                        ? null
                        : new AbstractMap.SimpleEntry<>(section.header.getName().trim(), section.header.sh_size))
                .filter(entry -> entry != null && idxMap.containsKey(entry.getKey()))
                .collect(toMap(
                        SimpleEntry::getKey,
                        SimpleEntry::getValue,
                        (existing, replacement) -> existing
                ));

        double[] sectionSizes = new double[idxMap.size()];
        Arrays.fill(sectionSizes, 0.0);
        idxMap.forEach((name, idx) -> sectionSizes[idx] = (double) sectionSizeMap.getOrDefault(name, 0L) / (double) size);

        return sectionSizes;
    }


    /**
     * Builds a fixed‐length numeric vector from the ELF header fields.
     * Booleans are mapped to 0/1, integers cast to double.
     *
     * @param elf the parsed ELF file
     * @return an array of doubles representing header features in this order:
     * [class(0=32,1=64), data(0=LSB,1=MSB), version, osAbi,
     * abiVersion, type, machine,
     * entryPoint, phOff, shOff, flags,
     * ehSize, phEntrySize, phCount, shEntrySize, shCount, shStrIdx]
     */
    private static double[] extractHeaderVector(ElfFile elf) {
        return new double[]{
                elf.ei_class == ElfFile.CLASS_32 ? 0.0 : 1.0,   // the architecture for the binary
                elf.ei_data == ElfFile.DATA_LSB ? 0.0 : 1.0,   // the data encoding of the processor-specific data in the file
                (double) elf.ei_version,    //  the version number of the ELF specification
                (double) elf.ei_osabi,      // the operating system and ABI to which the object is targeted
                (double) elf.es_abiversion, // the version of the ABI to which the object is targeted
                (double) elf.e_type,        // identifies the object file type
                (double) elf.e_machine,     // the required architecture for an individual file
                (double) elf.e_version,     // the file version
                (double) elf.e_entry,       // the virtual address to which the system first transfers control
                (double) elf.e_phoff,       // the program header table's file offset in bytes
                (double) elf.e_shoff,       // the section header table's file offset in bytes
                (double) elf.e_flags,       // processor-specific flags associated with the file
                (double) elf.e_ehsize,      // the ELF header's size in bytes
                (double) elf.e_phentsize,   // the size in bytes of one entry in the file's program header table
                (double) elf.e_phnum,       // the number of entries in the program header table
                (double) elf.e_shentsize,   // a section header's size in bytes
                (double) elf.e_shnum,       // the number of entries in the section header table
                (double) elf.e_shstrndx     // the section header table index of the entry associated with the section name string table
        };
    }

    /**
     * Extracts all “real” null-terminated strings from STRTAB sections
     * (excluding the section-name table) in the given ElfFile, using streams.
     *
     * @param elf the parsed ELF file
     * @return a List of non-empty strings found in all STRTAB sections
     */
    private static List<String> extractStrings(ElfFile elf) {
        return range(0, elf.e_shnum)
                .filter(i -> elf.getSection(i).header.sh_type == SHT_STRTAB && i != elf.e_shstrndx)
                .mapToObj(i -> elf.getSection(i).getData())
                .flatMap(raw -> Arrays.stream(new String(raw, StandardCharsets.ISO_8859_1).split("\0", -1)))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(toList());
    }

    /**
     * Maps each input string to a token in [0, MINHASH_DICT_SIZE−1] using
     * floorMod(s.hashCode(), MINHASH_DICT_SIZE) and removes duplicates.
     *
     * @param strings strings to tokenize
     * @return unique set of tokens
     */
    public static Set<Integer> mapStringsToTokens(List<String> strings) {
        Set<Integer> tokenSet = new HashSet<>();
        for (String s : strings) {
            int rawHash = s.hashCode();
            int token = Math.floorMod(rawHash, MINHASH_DICT_SIZE);
            tokenSet.add(token);
        }
        return tokenSet;
    }





    // ---------------------------- DEBUG ----------------------------

    private static void printStrings(ByteBuffer buf) {
        System.out.println("    String-Table:");
        buf.rewind();
        while (buf.hasRemaining()) {
            StringBuilder sb = new StringBuilder();
            byte b;
            while (buf.hasRemaining() && (b = buf.get()) != 0) {
                sb.append((char) b);
            }
            if (!sb.isEmpty()) {
                System.out.println("      " + sb);
            }
        }
    }

    private static void debugPrint(ElfFile elfFile) {
        // ELF-Header vollständig
        System.out.println("ELF Header:");
        System.out.printf("  Class:       %s-bit%n", elfFile.ei_class == ElfFile.CLASS_32 ? "32" : "64");
        System.out.printf("  Encoding:    %s%n", elfFile.ei_data == ElfFile.DATA_LSB ? "LSB" : "MSB");
        System.out.printf("  Version:     %d%n", elfFile.ei_version);
        System.out.printf("  OS/ABI:      %d%n", elfFile.ei_osabi);
        System.out.printf("  ABI-Version: %d%n", elfFile.es_abiversion);
        System.out.printf("  Type:        %d%n", elfFile.e_type);
        System.out.printf("  Machine:     %d%n", elfFile.e_machine);
        System.out.printf("  Entry:       0x%x%n", elfFile.e_entry);
        System.out.printf("  PhOff:       0x%x%n", elfFile.e_phoff);
        System.out.printf("  ShOff:       0x%x%n", elfFile.e_shoff);
        System.out.printf("  Flags:       0x%x%n", elfFile.e_flags);
        System.out.printf("  EHSize:      %d%n", elfFile.e_ehsize);
        System.out.printf("  PhEntrySize: %d, PhCount: %d%n", elfFile.e_phentsize, elfFile.e_phnum);
        System.out.printf("  ShEntrySize: %d, ShCount: %d%n", elfFile.e_shentsize, elfFile.e_shnum);
        System.out.printf("  ShStrIdx:    %d%n", elfFile.e_shstrndx);


        // Program-Headers
        System.out.println("Program Headers:");
        for (int i = 0; i < elfFile.e_phnum; i++) {
            ElfSegment ph = elfFile.getProgramHeader(i);
            System.out.printf(
                    "  [%2d] Type=0x%x Off=0x%x VAddr=0x%x PAddr=0x%x%n" +
                            "       FileSz=0x%x MemSz=0x%x Flags=0x%x Align=0x%x%n",
                    i, ph.p_type, ph.p_offset, ph.p_vaddr, ph.p_paddr,
                    ph.p_filesz, ph.p_memsz, ph.p_flags, ph.p_align
            );
        }

        // Section-Headers + String-Tabellen
        System.out.println("\nSection Headers:");
        for (int i = 0; i < elfFile.e_shnum; i++) {
            ElfSection sec = elfFile.getSection(i);
            ElfSectionHeader sh = sec.header;
            System.out.printf(
                    "  [%2d] %-15s Type=%d Flags=0x%x Addr=0x%x Off=0x%x Size=0x%x%n" +
                            "       Link=%d Info=%d Align=%d EntSize=%d%n",
                    i, sh.getName(), sh.sh_type, sh.sh_flags,
                    sh.sh_addr, sh.sh_offset, sh.sh_size,
                    sh.sh_link, sh.sh_info, sh.sh_addralign, sh.sh_entsize
            );

            if (sh.sh_type == SHT_STRTAB) {
                // getData liefert byte[]
                ByteBuffer buf = ByteBuffer.wrap(sec.getData());
                printStrings(buf);
            }
        }

        // Symbol-Tabellen (.symtab + .dynsym)
        System.out.println("\nSymbol Tables:");
        ElfSymbolTableSection symtab = elfFile.getSymbolTableSection();
        ElfSymbolTableSection dynsym = elfFile.getDynamicSymbolTableSection();
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
        ElfDynamicSection dyn = elfFile.getDynamicSection();
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
    }
}
