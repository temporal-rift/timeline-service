package io.github.temporalrift.timeline.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.timeline.domain.membership.NotGameParticipantException;
import io.github.temporalrift.timeline.domain.membership.NotWeaverException;
import io.github.temporalrift.timeline.domain.weaverchain.ChainNotFoundException;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChainNotFoundException;
import io.github.temporalrift.timeline.shared.ProblemDetails;
import io.github.temporalrift.timeline.shared.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = ChainsController.class)
class ChainsExceptionHandler {

    @ExceptionHandler(NotGameParticipantException.class)
    ProblemDetail handleNotGameParticipant(NotGameParticipantException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "404-01");
    }

    @ExceptionHandler(NotWeaverException.class)
    ProblemDetail handleNotWeaver(NotWeaverException ex) {
        return ProblemDetails.of(HttpStatus.FORBIDDEN, ex.getMessage(), "403-01");
    }

    @ExceptionHandler(ChainNotFoundException.class)
    ProblemDetail handleChainNotFound(ChainNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "404-02");
    }

    @ExceptionHandler(WeaverChainNotFoundException.class)
    ProblemDetail handleWeaverChainNotFound(WeaverChainNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "404-02");
    }
}
