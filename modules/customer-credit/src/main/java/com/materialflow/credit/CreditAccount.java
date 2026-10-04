package com.materialflow.credit;

import java.math.BigDecimal;

public record CreditAccount(
        BigDecimal creditLimit,
        BigDecimal creditReserved,
        BigDecimal creditUsed,
        BigDecimal overdueAmount,
        boolean frozen,
        long version) {

    public CreditAccount {
        creditLimit = requireNonNegative(creditLimit, "creditLimit");
        creditReserved = requireNonNegative(creditReserved, "creditReserved");
        creditUsed = requireNonNegative(creditUsed, "creditUsed");
        overdueAmount = requireNonNegative(overdueAmount, "overdueAmount");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public BigDecimal available() {
        return creditLimit.subtract(creditReserved).subtract(creditUsed);
    }

    public CreditAccount reserve(BigDecimal amount, boolean approvedOverride) {
        amount = requirePositive(amount, "amount");
        if (!approvedOverride && (frozen || available().compareTo(amount) < 0)) {
            throw new CreditLimitExceededException();
        }
        return new CreditAccount(creditLimit, creditReserved.add(amount), creditUsed, overdueAmount, frozen, version + 1);
    }

    public CreditAccount release(BigDecimal amount) {
        amount = requirePositive(amount, "amount");
        if (creditReserved.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Cannot release more credit than is reserved");
        }
        return new CreditAccount(creditLimit, creditReserved.subtract(amount), creditUsed, overdueAmount, frozen, version + 1);
    }

    public CreditAccount capture(BigDecimal amount) {
        amount = requirePositive(amount, "amount");
        if (creditReserved.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Cannot capture more credit than is reserved");
        }
        return new CreditAccount(creditLimit, creditReserved.subtract(amount), creditUsed.add(amount), overdueAmount, frozen, version + 1);
    }

    public CreditAccount settle(BigDecimal amount) {
        amount = requirePositive(amount, "amount");
        if (creditUsed.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Cannot settle more credit than is used");
        }
        return new CreditAccount(creditLimit, creditReserved, creditUsed.subtract(amount), overdueAmount, frozen, version + 1);
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public static final class CreditLimitExceededException extends RuntimeException {
        public CreditLimitExceededException() {
            super("Credit limit is insufficient or the account is frozen");
        }
    }
}

