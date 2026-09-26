package com.bombay.restaurantintelligence.storage;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import java.nio.file.*; import java.util.UUID;
@Service @ConditionalOnProperty(name="app.storage.mode",havingValue="local",matchIfMissing=true) public class LocalDocumentStorageService implements DocumentStorageService {
 private final Path root; public LocalDocumentStorageService(@Value("${app.storage.local-root:./storage}") String root){this.root=Path.of(root).toAbsolutePath().normalize();}
 @Override public String store(String filename,byte[] content){try{Files.createDirectories(root);String safe=(filename==null?"document":filename).replaceAll("[^a-zA-Z0-9._-]","_");Path p=root.resolve(UUID.randomUUID()+"-"+safe);Files.write(p,content,StandardOpenOption.CREATE_NEW);return p.toString();}catch(Exception e){throw new IllegalStateException("Could not store document",e);}}
 @Override public byte[] read(String location){try{return Files.readAllBytes(Path.of(location));}catch(Exception e){throw new IllegalStateException("Could not read document",e);}}
}
