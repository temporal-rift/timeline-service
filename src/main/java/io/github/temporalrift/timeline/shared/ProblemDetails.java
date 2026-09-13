package io.github.temporalrift.timeline.shared;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Creates application problem details with the shared stable error-code extension. */
public final class ProblemDetails {

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatusCode status, String detail, String code) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
