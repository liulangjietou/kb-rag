package io.kbrag.api.controller;

import io.kbrag.api.dto.ModelStatusResponse;
import io.kbrag.app.system.ModelStatusService;
import io.kbrag.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System information endpoints.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final ModelStatusService modelStatusService;

    /**
     * Reports whether an embedding provider is configured and which engine backs the vector route.
     *
     * @return model status
     */
    @GetMapping("/model-status")
    public Result<ModelStatusResponse> modelStatus() {
        return Result.success(ModelStatusResponse.from(modelStatusService.current()));
    }
}
