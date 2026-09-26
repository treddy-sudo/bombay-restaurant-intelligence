package com.bombay.restaurantintelligence.storage;

public interface DocumentStorageService {
    String store(String filename, String contentType, byte[] content);

    default String store(String filename, byte[] content) {
        return store(filename, "application/octet-stream", content);
    }

    byte[] read(String location);

    default void verifyAvailable() {
        // Implementations may perform a non-destructive readiness check.
    }
}
