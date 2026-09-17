package com.groupeat.domain.payment.enums;

public enum TossPaymentStatus {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED,
    UNKNOWN;

    public static TossPaymentStatus from(String status) {
        for (TossPaymentStatus value : values()) {
            if (value.name().equals(status)) {
                return value;
            }
        }
        return UNKNOWN;
    }

    public boolean isFailed() {
        return this == ABORTED || this == EXPIRED;
    }
}
