package com.groupeat.domain.payment.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

import java.util.Set;

@Getter
public class TossPaymentException extends RuntimeException {

    private static final Set<String> PROCESSING_CODES = Set.of(
            "IDEMPOTENT_REQUEST_PROCESSING", "ALREADY_PROCESSING_REQUEST"
    );
    // 실패가 명확한 코드만 확정하고, 나머지는 결제 상태 조회
    private static final Set<String> DEFINITIVE_FAILURE_CODES = Set.of(
            "REJECT_CARD_PAYMENT", "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_COMPANY",
            "NOT_FOUND_PAYMENT_SESSION", "EXPIRED_SESSION"
    );

    private final HttpStatusCode httpStatusCode;
    private final String tossErrorCode;
    private final String tossErrorMessage;

    public TossPaymentException(HttpStatusCode httpStatusCode, String tossErrorCode, String tossErrorMessage) {
        super(tossErrorMessage);
        this.httpStatusCode = httpStatusCode;
        this.tossErrorCode = tossErrorCode;
        this.tossErrorMessage = tossErrorMessage;
    }

    public boolean isProcessing() {
        return tossErrorCode != null && PROCESSING_CODES.contains(tossErrorCode);
    }

    public boolean isDefinitiveFailure() {
        return httpStatusCode.is4xxClientError()
                && tossErrorCode != null
                && DEFINITIVE_FAILURE_CODES.contains(tossErrorCode);
    }
}
