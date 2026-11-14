package ro.filegrid.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.filegrid.policy.PolicyFileWatcher.FileStatus;
import ro.filegrid.policy.PolicyService.ActivePolicy;
import ro.filegrid.policy.PolicyService.InvalidPolicyException;
import ro.filegrid.policy.PolicyService.PolicySource;
import ro.filegrid.policy.PolicyService.SubmitResult;
import ro.filegrid.policy.PolicyService.VersionSummary;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.text.Texts;

/** Reading the active routing policy and its history, and changing it without a restart. */
@RestController
@Profile("api")
public class PolicyController {

    public static final String PERMISSION_READ = "policy:read";
    public static final String PERMISSION_WRITE = "policy:write";
    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyService policies;
    private final PolicyFileWatcher fileWatcher;
    private final CurrentActorProvider actor;
    private final AccessPolicy accessPolicy;
    private final Texts texts;

    public PolicyController(PolicyService policies, PolicyFileWatcher fileWatcher, CurrentActorProvider actor,
                            AccessPolicy accessPolicy, Texts texts) {
        this.policies = policies;
        this.fileWatcher = fileWatcher;
        this.actor = actor;
        this.accessPolicy = accessPolicy;
        this.texts = texts;
    }

    /** The policy in force. */
    @GetMapping("/policies")
    public PolicyView active() {
        require(PERMISSION_READ);
        return view(policies.active(), true);
    }

    /** Versions, newest first, each with what changed against the one before it. */
    @GetMapping("/policies/history")
    public HistoryView history(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        require(PERMISSION_READ);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page >= 0, 1 <= size <= " + MAX_PAGE_SIZE);
        }
        return new HistoryView(policies.history(page, size), page, size, policies.versionCount());
    }

    /**
     * Replaces the policy with the YAML document sent as the request body. The
     * document is validated first; a refused document changes nothing.
     */
    @PutMapping(value = "/admin/policies",
            consumes = {"text/plain", "application/yaml", "application/x-yaml", "text/yaml"})
    public PolicyView replace(@RequestBody String yaml) {
        String who = actor.currentActor();
        if (!accessPolicy.isAllowed(who, PERMISSION_WRITE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        SubmitResult result = policies.submit(yaml, PolicySource.ADMIN, who);
        return view(result.policy(), result.changed());
    }

    @ExceptionHandler(InvalidPolicyException.class)
    ProblemDetail invalidPolicy(InvalidPolicyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                texts.get("policy.error.refused"));
        problem.setTitle(texts.get("policy.error.refused.title"));
        problem.setProperty("errors", e.errors());
        return problem;
    }

    private void require(String permission) {
        if (!accessPolicy.isAllowed(actor.currentActor(), permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private PolicyView view(ActivePolicy active, boolean changed) {
        return new PolicyView(active.version(), active.label(), active.source(), active.author(),
                active.createdAt(), active.contentSha256(), changed, PolicyParser.toJson(active.policy()),
                active.content(), fileWatcher.status());
    }

    /**
     * @param changed {@code false} only when a PUT sent the text that was already active
     * @param policy  the policy as structured data
     * @param yaml    the exact text of this version
     * @param file    state of {@code config/policies.yaml} (why it was refused, if it was)
     */
    public record PolicyView(long version, String label, PolicySource source, String author, Instant createdAt,
                             String contentSha256, boolean changed, JsonNode policy, String yaml,
                             FileStatus file) {
    }

    public record HistoryView(List<VersionSummary> items, int page, int size, long total) {
    }
}
