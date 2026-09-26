package com.bombay.restaurantintelligence.web;

import com.bombay.restaurantintelligence.domain.SourceColumnMapping;
import com.bombay.restaurantintelligence.repository.SourceColumnMappingRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/source-column-mappings")
public class SourceColumnMappingController {
    private final SourceColumnMappingRepository repo;

    public SourceColumnMappingController(SourceColumnMappingRepository repo){
        this.repo=repo;
    }

    @GetMapping
    public List<View> list(@RequestParam String sourceKey){
        return repo.findBySourceKeyIgnoreCaseOrderBySourceColumn(sourceKey).stream().map(View::of).toList();
    }

    @PostMapping
    public View save(@Valid @RequestBody Request request){
        SourceColumnMapping mapping=repo
                .findFirstBySourceKeyIgnoreCaseAndSourceColumnIgnoreCase(request.sourceKey(),request.sourceColumn())
                .map(existing->{
                    existing.updateCanonicalField(request.canonicalField());
                    return existing;
                })
                .orElseGet(()->new SourceColumnMapping(request.sourceKey(),request.sourceColumn(),request.canonicalField()));
        return View.of(repo.save(mapping));
    }

    public record Request(@NotBlank String sourceKey,@NotBlank String sourceColumn,@NotBlank String canonicalField){}
    public record View(UUID id,String sourceKey,String sourceColumn,String canonicalField){
        static View of(SourceColumnMapping mapping){
            return new View(mapping.getId(),mapping.getSourceKey(),mapping.getSourceColumn(),mapping.getCanonicalField());
        }
    }
}
