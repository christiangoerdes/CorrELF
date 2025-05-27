package com.goerdes.correlf.handler;

import com.goerdes.correlf.exception.FileProcessingException;

import java.nio.file.Path;

public interface FileHandler {

    /**
     * Determines whether this handler supports processing the specified file.
     *
     * @param path the path to the file
     * @return {@code true} if this handler can process the file; {@code false} otherwise
     * @throws Exception if an error occurs while checking support
     */
    boolean supports(Path path) throws Exception;

    /**
     * Processes the given file.
     *
     * @param path the path to the file to handle
     * @throws FileProcessingException if the file cannot be processed
     */
    void handle(Path path) throws FileProcessingException;

}
