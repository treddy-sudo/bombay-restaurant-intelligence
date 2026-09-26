package com.bombay.restaurantintelligence.storage;

public interface DocumentStorageService {
    String store(String filename, byte[] content);
    byte[] read(String location);

    default void verifyAvailable() {
        // Implementations may perform a non-destructive readiness check.
    }
}
