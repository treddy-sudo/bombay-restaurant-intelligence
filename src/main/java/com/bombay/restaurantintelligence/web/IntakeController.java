package com.bombay.restaurantintelligence.web;
import com.bombay.restaurantintelligence.intake.*; import com.bombay.restaurantintelligence.normalization.NormalizationResult; import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank; import org.springframework.http.MediaType; import org.springframework.web.bind.annotation.*; import org.springframework.web.multipart.MultipartFile; import java.security.Principal; import java.util.UUID;
@RestController @RequestMapping("/api/intake") public class IntakeController {
 private final IntakeAgent intake; private final UploadIngestionService uploads; public IntakeController(IntakeAgent intake,UploadIngestionService uploads){this.intake=intake;this.uploads=uploads;}
 @PostMapping("/manual-text") public NormalizationResult manual(@Valid @RequestBody ManualTextRequest request,Principal p){return intake.ingestManualText(request.text(),p.getName());}
 @PostMapping(value="/uploads/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public UploadIngestionService.PreviewResponse preview(@RequestPart("file") MultipartFile file){return uploads.preview(file);}
 @PostMapping("/uploads/{jobId}/confirm") public UploadIngestionService.ConfirmResponse confirm(@PathVariable UUID jobId){return uploads.confirm(jobId);}
 public record ManualTextRequest(@NotBlank String text){}
}
