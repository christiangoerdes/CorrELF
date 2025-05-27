package com.goerdes.correlf.services;

import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.handler.FileHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FileHandlerRegistry {

    /** All available file handlers to choose from. */
    private final List<FileHandler> handlers;

    /**
     * Finds and returns the first {@link FileHandler} that supports the given path.
     * Any exceptions thrown by a handler’s {@code supports} method are caught
     * and treated as non-supportive.
     *
     * @param path the file system path to check
     * @return the first supporting {@code FileHandler}
     * @throws FileProcessingException if no handler supports the provided path
     */
    public FileHandler getHandler(Path path) {
        return handlers.stream()
                .filter(h -> {
                    try {
                        return h.supports(path);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new FileProcessingException("No registered FileHandler supports: " + path, null));
    }

}
