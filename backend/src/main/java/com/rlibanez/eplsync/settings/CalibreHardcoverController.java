package com.rlibanez.eplsync.settings;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/calibre/hardcover")
@PreAuthorize("hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_SEND')")
public class CalibreHardcoverController {
    private final CalibreHardcover service;
    public CalibreHardcoverController(CalibreHardcover service) {this.service=service;}
    public record Search(long calibreId,String token,int page,Boolean useTitle,Boolean useAuthor,Boolean useIsbn,Boolean useAsin,String queryTitle,String queryAuthor,String queryIsbn,String queryAsin) {}
    public record Preview(String token,long bookId,Long editionId,Map<CalibreHardcover.Field,String> mappings) {}
    public record Token(String token) {}
    public record Slug(String token,String slug) {}
    public record EditionId(String token,long editionId) {}
    @PostMapping("/resolve-edition") public HardcoverClient.EditionBook resolveEdition(@RequestBody EditionId input) {return service.resolveEdition(input.token(),input.editionId());}
    @PostMapping("/resolve-slug") public HardcoverClient.Match resolveSlug(@RequestBody Slug input) {return service.resolveSlug(input.token(),input.slug());}
    @GetMapping public CalibreHardcover.Configuration configuration() {return service.configuration();}
    @PutMapping public CalibreHardcover.Configuration configure(@RequestBody Map<CalibreHardcover.Field,String> mappings) {return service.configure(mappings);}
    @PostMapping("/search") public CalibreHardcover.Matches search(@RequestBody Search input) {return service.search(input.calibreId(),input.token(),input.page(),input.useTitle()==null || input.useTitle(),input.useAuthor()==null || input.useAuthor(),Boolean.TRUE.equals(input.useIsbn()),Boolean.TRUE.equals(input.useAsin()),input.queryTitle(),input.queryAuthor(),input.queryIsbn(),input.queryAsin());}
    @GetMapping("/cover")
    @PreAuthorize("hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_SEND') and hasAuthority('CALIBRE_FILES_READ')")
    public org.springframework.http.ResponseEntity<byte[]> cover(@RequestParam String token) {
        var image=service.cover(token);
        var headers=new org.springframework.http.HttpHeaders();headers.setCacheControl("private, no-store");headers.set("X-Content-Type-Options","nosniff");
        if(image.bytes().length==0) return new org.springframework.http.ResponseEntity<>(headers,org.springframework.http.HttpStatus.NOT_FOUND);
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(image.contentType()));
        return new org.springframework.http.ResponseEntity<>(image.bytes(),headers,org.springframework.http.HttpStatus.OK);
    }
    @GetMapping("/editions") public HardcoverClient.Editions editions(@RequestParam String token,@RequestParam long bookId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") String language) {return service.editions(token,bookId,page,language);}
    @GetMapping("/edition-languages") public java.util.List<HardcoverClient.Language> editionLanguages(@RequestParam String token,@RequestParam long bookId) {return service.editionLanguages(token,bookId);}
    @PostMapping("/preview") public CalibreHardcover.Preview preview(@RequestBody Preview input) {return service.preview(input.token(),input.bookId(),input.editionId(),input.mappings());}
    @PostMapping("/apply") public CalibreHardcover.Preview apply(@RequestBody Token input) {return service.apply(input.token());}
    @PostMapping("/discard") public Map<String,Boolean> discard(@RequestBody Token input) {service.discard(input.token());return Map.of("discarded",true);}
}
