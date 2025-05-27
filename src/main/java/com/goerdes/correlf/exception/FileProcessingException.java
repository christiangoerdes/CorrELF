package com.goerdes.correlf.exception;

public class FileProcessingException extends RuntimeException {
    public FileProcessingException(String message, Exception e) {
        super(message, e);
    }
}
