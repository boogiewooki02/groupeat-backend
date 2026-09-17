ALTER TABLE public.payments
    DROP CONSTRAINT payments_payment_status_check;

ALTER TABLE public.payments
    ADD CONSTRAINT payments_payment_status_check
    CHECK (payment_status IN (
        'READY', 'IN_PROGRESS', 'RECONCILIATION_REQUIRED', 'DONE',
        'FAILED', 'CANCELED', 'PARTIAL_CANCELED'
    ));
