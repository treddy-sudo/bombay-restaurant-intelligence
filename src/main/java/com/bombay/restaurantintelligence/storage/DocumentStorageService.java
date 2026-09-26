package com.bombay.restaurantintelligence.storage;
public interface DocumentStorageService { String store(String filename, byte[] content); byte[] read(String location); }
