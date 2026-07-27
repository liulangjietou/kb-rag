package io.kbrag.api.controller;

import io.kbrag.api.dto.SourceMappingCopyRequest;
import io.kbrag.api.dto.SourceMappingRequest;
import io.kbrag.api.dto.SourceMappingResponse;
import io.kbrag.app.chat.SourceMappingService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.SourceMappingType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Chat import mapping profile endpoints, requirement section 4.2 "field mapping maintenance".
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/source-mappings")
@RequiredArgsConstructor
public class SourceMappingController {

    private final SourceMappingService sourceMappingService;

    /**
     * Lists the mapping profiles.
     *
     * <p>A bare array rather than a page envelope: the rows are the export formats this deployment can
     * read, which is a handful, and the console fills a dropdown from them in one call.
     *
     * @param sourceType optional export format filter
     * @return profiles, built-in ones first
     */
    @GetMapping
    public Result<List<SourceMappingResponse>> list(
            @RequestParam(name = "source_type", required = false) String sourceType) {
        return Result.success(sourceMappingService.list(parseType(sourceType)).stream()
                .map(SourceMappingResponse::from)
                .toList());
    }

    /**
     * Creates a custom mapping profile.
     *
     * @param request profile payload
     * @return created profile
     */
    @PostMapping
    public Result<SourceMappingResponse> create(@Valid @RequestBody SourceMappingRequest request) {
        return Result.success(SourceMappingResponse.from(sourceMappingService.create(
                request.name(), request.resolvedType(), request.profileYaml())));
    }

    /**
     * Replaces a custom mapping profile in full.
     *
     * @param mappingId business identifier
     * @param request   new profile payload
     * @return updated profile
     */
    @PutMapping("/{mappingId}")
    public Result<SourceMappingResponse> update(@PathVariable String mappingId,
                                                @Valid @RequestBody SourceMappingRequest request) {
        return Result.success(SourceMappingResponse.from(sourceMappingService.update(
                mappingId, request.name(), request.resolvedType(), request.profileYaml())));
    }

    /**
     * Copies a mapping profile into a new custom one.
     *
     * <p>The only way to change a built-in template: the copy is a row no seeding pass will overwrite, so
     * an operator tuning a regular expression against their own export does not lose the edit on the next
     * release that recalibrates the template.
     *
     * @param mappingId business identifier of the profile being copied
     * @param request   optional name of the copy, absent generating one
     * @return created profile
     */
    @PostMapping("/{mappingId}/copy")
    public Result<SourceMappingResponse> copy(
            @PathVariable String mappingId,
            @RequestBody(required = false) SourceMappingCopyRequest request) {
        return Result.success(SourceMappingResponse.from(
                sourceMappingService.copy(mappingId, request == null ? null : request.name())));
    }

    /**
     * Removes a custom mapping profile.
     *
     * @param mappingId business identifier
     * @return empty success envelope
     */
    @DeleteMapping("/{mappingId}")
    public Result<Void> delete(@PathVariable String mappingId) {
        sourceMappingService.delete(mappingId);
        return Result.success(null);
    }

    private SourceMappingType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        SourceMappingType resolved = SourceMappingType.from(value);
        if (resolved == null) {
            throw BizException.invalidParam("unknown source_type: " + value);
        }
        return resolved;
    }
}
