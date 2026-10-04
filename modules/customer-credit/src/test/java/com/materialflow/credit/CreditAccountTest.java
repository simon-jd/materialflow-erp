package com.materialflow.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CreditAccountTest {
    @Test
    void reserveCaptureAndSettlePreserveCreditLedgerInvariant() {
        var account = account("1000", "0", "100").reserve(new BigDecimal("500"), false);
        assertThat(account.available()).isEqualByComparingTo("400");

        account = account.capture(new BigDecimal("200"));
        assertThat(account.creditReserved()).isEqualByComparingTo("300");
        assertThat(account.creditUsed()).isEqualByComparingTo("300");

        account = account.settle(new BigDecimal("50"));
        assertThat(account.creditUsed()).isEqualByComparingTo("250");
    }

    @Test
    void ordinaryReservationCannotExceedLimit() {
        assertThatThrownBy(() -> account("100", "80", "10").reserve(new BigDecimal("11"), false))
                .isInstanceOf(CreditAccount.CreditLimitExceededException.class);
    }

    private CreditAccount account(String limit, String reserved, String used) {
        return new CreditAccount(new BigDecimal(limit), new BigDecimal(reserved), new BigDecimal(used),
                BigDecimal.ZERO, false, 0);
    }
}

