package ru.tinvest.trainer.sessions;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Воспроизведение завершённой сессии (контракт {@code api/openapi.yaml}, {@code getReplay}). */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/replay")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ReplayController {

    private final Replay replay;

    ReplayController(Replay replay) {
        this.replay = replay;
    }

    @GetMapping
    Map<String, Object> replay(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") long step,
            @RequestParam(required = false) @Nullable Long fromStep) {
        return replay.replay(sessionId, step, fromStep);
    }
}
