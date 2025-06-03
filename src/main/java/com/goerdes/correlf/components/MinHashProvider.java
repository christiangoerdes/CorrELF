package com.goerdes.correlf.components;

import info.debatty.java.lsh.MinHash;
import org.springframework.stereotype.Component;

/**
 * Provides a singleton MinHash instance with a fixed seed for consistent
 * MinHash signature generation and similarity estimation across the application.
 */
@Component
public class MinHashProvider {

    private static final long SEED = 123456789L;

    public static final Integer MINHASH_DICT_SIZE = 50000;

    private final MinHash minhash;

    public MinHashProvider() {
        this.minhash = new MinHash(128, MINHASH_DICT_SIZE, SEED);
    }

    /**
     * Returns the shared MinHash instance.
     *
     * @return the MinHash object
     */
    public MinHash get() {
        return minhash;
    }
}
