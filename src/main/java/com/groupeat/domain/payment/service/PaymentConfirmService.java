package com.groupeat.domain.payment.service;

import com.groupeat.domain.payment.client.TossPaymentClient;
import com.groupeat.domain.payment.dto.PreparedPaymentConfirm;
import com.groupeat.domain.payment.dto.PaymentCancelResult;
import com.groupeat.domain.payment.dto.request.PaymentConfirmRequest;
import com.groupeat.domain.payment.dto.response.PaymentConfirmResponse;
import com.groupeat.domain.payment.dto.toss.TossPaymentConfirmRequest;
import com.groupeat.domain.payment.dto.toss.TossPaymentCancelRequest;
import com.groupeat.domain.payment.dto.toss.TossPaymentConfirmResponse;
import com.groupeat.domain.payment.enums.TossPaymentStatus;
import com.groupeat.domain.payment.exception.PaymentErrorStatus;
import com.groupeat.domain.payment.exception.TossPaymentException;
import com.groupeat.global.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentConfirmService {

    private static final String RECONCILIATION_FAILED_CODE = "PAYMENT_RECONCILIATION_FAILED";
    private static final String INVALID_CONFIRM_RESPONSE_CODE = "INVALID_TOSS_CONFIRM_RESPONSE";
    private static final String APPROVAL_FAILURE_CANCEL_REASON = "결제 승인 후 주문 처리 실패로 자동 취소";

    private final TossPaymentClient tossPaymentClient;
    private final PaymentConfirmTransactionService paymentConfirmTransactionService;

    public PaymentConfirmResponse confirm(Long memberId, String idempotencyKey, PaymentConfirmRequest request) {
        PreparedPaymentConfirm preparedPayment = paymentConfirmTransactionService.prepareConfirm(memberId, idempotencyKey, request);
        if (preparedPayment.alreadyConfirmedResponse() != null) {
            return preparedPayment.alreadyConfirmedResponse();
        }

        TossPaymentConfirmResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.confirmPayment(
                    TossPaymentConfirmRequest.from(request),
                    idempotencyKey
            );
        } catch (TossPaymentException e) {
            if (e.isProcessing()) {
                throw new GeneralException(PaymentErrorStatus.PAYMENT_CONFIRM_IN_PROGRESS);
            }

            if (e.isDefinitiveFailure()) {
                paymentConfirmTransactionService.failPayment(preparedPayment.paymentId(), e.getTossErrorCode(), e.getTossErrorMessage());
                throw new GeneralException(PaymentErrorStatus.TOSS_CONFIRM_FAILED);
            }
            return reconcilePayment(preparedPayment, request.paymentKey(), e);
        } catch (RestClientException e) {
            return reconcilePayment(preparedPayment, request.paymentKey(), e);
        }

        if (tossResponse == null) {
            return reconcilePayment(preparedPayment, request.paymentKey(),
                    new IllegalStateException("토스 승인 응답이 비어 있습니다."));
        }

        validateTossConfirmResponse(tossResponse, preparedPayment);
        try {
            return paymentConfirmTransactionService.approvePayment(preparedPayment.paymentId(), tossResponse);
        } catch (RuntimeException e) {
            return reconcilePayment(preparedPayment, request.paymentKey(), e);
        }
    }

    private PaymentConfirmResponse reconcilePayment(
            PreparedPaymentConfirm preparedPayment, String paymentKey, RuntimeException initialFailure
    ) {
        log.warn("결제 상태 재조회 및 내부 반영 시도. paymentId={}, orderId={}, initialFailure={}",
                preparedPayment.paymentId(), preparedPayment.orderId(), describeFailure(initialFailure), initialFailure);
        boolean approvalConfirmed = false;
        try {
            TossPaymentConfirmResponse payment = tossPaymentClient.getPayment(paymentKey);
            if (payment == null
                    || !paymentKey.equals(payment.paymentKey())
                    || !preparedPayment.orderId().equals(payment.orderId())
                    || !preparedPayment.paidAmount().equals(payment.totalAmount())) {
                throw new IllegalStateException("토스 재조회 결과가 결제 요청과 일치하지 않습니다.");
            }
            TossPaymentStatus status = TossPaymentStatus.from(payment.status());
            if (status == TossPaymentStatus.DONE) {
                approvalConfirmed = true;
                return paymentConfirmTransactionService.approvePayment(preparedPayment.paymentId(), payment);
            }
            if (status.isFailed()) {
                paymentConfirmTransactionService.failPayment(
                        preparedPayment.paymentId(),
                        payment.failure() != null ? payment.failure().code() : payment.status(),
                        payment.failure() != null ? payment.failure().message() : "토스 결제 승인 실패 또는 만료"
                );
            } else {
                throw new IllegalStateException("토스 결제 결과 확인이 필요합니다. status=" + payment.status());
            }
        } catch (RuntimeException e) {
            if (approvalConfirmed) {
                return cancelAfterApprovalFailure(preparedPayment, paymentKey, initialFailure, e);
            }
            recordReconciliationRequired(preparedPayment, initialFailure, e);
            throw new GeneralException(PaymentErrorStatus.PAYMENT_RECONCILIATION_REQUIRED);
        }

        throw new GeneralException(PaymentErrorStatus.TOSS_CONFIRM_FAILED);
    }

    private PaymentConfirmResponse cancelAfterApprovalFailure(
            PreparedPaymentConfirm preparedPayment, String paymentKey,
            RuntimeException initialFailure, RuntimeException approvalFailure
    ) {
        try {
            PaymentConfirmResponse confirmed = paymentConfirmTransactionService.prepareApprovalFailureCancel(
                    preparedPayment.paymentId(), paymentKey);
            if (confirmed != null) {
                return confirmed;
            }

            log.warn("결제 내부 반영 재시도 실패. 전액 취소를 시도합니다. paymentId={}, orderId={}",
                    preparedPayment.paymentId(), preparedPayment.orderId(), approvalFailure);
            TossPaymentConfirmResponse canceled = tossPaymentClient.cancelPayment(
                    paymentKey, TossPaymentCancelRequest.of(APPROVAL_FAILURE_CANCEL_REASON, null));
            validateFullCancelResponse(canceled, preparedPayment, paymentKey);

            PaymentCancelResult cancelResult = PaymentCancelResult.from(canceled, preparedPayment.paidAmount());
            log.info("토스 전액 취소 확인. paymentId={}, orderId={}, refundedAmount={}, transactionKey={}",
                    preparedPayment.paymentId(), preparedPayment.orderId(),
                    cancelResult.refundedAmount(), cancelResult.lastTransactionKey());
            paymentConfirmTransactionService.cancelAfterApprovalFailure(
                    preparedPayment.paymentId(), paymentKey, APPROVAL_FAILURE_CANCEL_REASON, cancelResult);
        } catch (RuntimeException cancelFailure) {
            RuntimeException recoveryFailure = new IllegalStateException(
                    "approvalFailure=%s; cancelFailure=%s"
                            .formatted(describeFailure(approvalFailure), describeFailure(cancelFailure)),
                    cancelFailure);
            recordReconciliationRequired(preparedPayment, initialFailure, recoveryFailure);
            throw new GeneralException(PaymentErrorStatus.PAYMENT_RECONCILIATION_REQUIRED);
        }

        throw new GeneralException(PaymentErrorStatus.PAYMENT_CANCELED_AFTER_CONFIRM_FAILURE);
    }

    private void validateFullCancelResponse(
            TossPaymentConfirmResponse response, PreparedPaymentConfirm preparedPayment, String paymentKey
    ) {
        if (response == null
                || TossPaymentStatus.from(response.status()) != TossPaymentStatus.CANCELED
                || !paymentKey.equals(response.paymentKey())
                || !preparedPayment.orderId().equals(response.orderId())
                || !preparedPayment.paidAmount().equals(response.totalAmount())
                || !Integer.valueOf(0).equals(response.balanceAmount())) {
            throw new IllegalStateException("토스 전액 취소 결과를 확인할 수 없습니다.");
        }
    }

    private void recordReconciliationRequired(
            PreparedPaymentConfirm preparedPayment, RuntimeException initialFailure, RuntimeException recoveryFailure
    ) {
        String failureMessage = "initialFailure=%s; recoveryFailure=%s"
                .formatted(describeFailure(initialFailure), describeFailure(recoveryFailure));
        log.error("결제 복구 실패. paymentId={}, orderId={}, failures={}",
                preparedPayment.paymentId(), preparedPayment.orderId(), failureMessage, recoveryFailure);
        try {
            paymentConfirmTransactionService.markReconciliationRequired(
                    preparedPayment.paymentId(), RECONCILIATION_FAILED_CODE, failureMessage);
        } catch (RuntimeException recordException) {
            log.error("결제 복구 필요 상태 기록 실패. paymentId={}, orderId={}, failures={}",
                    preparedPayment.paymentId(), preparedPayment.orderId(), failureMessage, recordException);
        }
    }

    private String describeFailure(RuntimeException failure) {
        if (failure instanceof TossPaymentException tossFailure) {
            return "%s[%s]: %s".formatted(failure.getClass().getSimpleName(),
                    tossFailure.getTossErrorCode(), failure.getMessage());
        }
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    // 토스 승인 성공 응답이 우리 결제 요청 정보와 일치하는지 검증
    private void validateTossConfirmResponse(TossPaymentConfirmResponse tossResponse, PreparedPaymentConfirm preparedPayment) {
        if (TossPaymentStatus.from(tossResponse.status()) != TossPaymentStatus.DONE
                || !preparedPayment.orderId().equals(tossResponse.orderId())
                || !preparedPayment.paidAmount().equals(tossResponse.totalAmount())) {
            // TODO: 결제 실패 보상 PR에서 토스 승인 성공 후 내부 검증 실패 시 취소/환불 처리 추가
            paymentConfirmTransactionService.failPayment(preparedPayment.paymentId(), INVALID_CONFIRM_RESPONSE_CODE, "토스페이먼츠 결제 승인 응답이 결제 요청 정보와 일치하지 않습니다.");
            throw new GeneralException(PaymentErrorStatus.TOSS_CONFIRM_FAILED);
        }
    }
}
