package com.materialflow.masterdata;

import java.math.BigDecimal;
import java.math.RoundingMode;

public enum UnitOfMeasure {
    PIECE("COUNT", BigDecimal.ONE),
    BOX("COUNT", new BigDecimal("12")),
    PALLET("COUNT", new BigDecimal("480")),
    METER("LENGTH", BigDecimal.ONE),
    SQUARE_METER("AREA", BigDecimal.ONE),
    TON("MASS", BigDecimal.ONE);

    private final String dimension;
    private final BigDecimal baseFactor;

    UnitOfMeasure(String dimension, BigDecimal baseFactor) {
        this.dimension = dimension;
        this.baseFactor = baseFactor;
    }

    public BigDecimal convert(BigDecimal quantity, UnitOfMeasure target) {
        if (!dimension.equals(target.dimension)) {
            throw new IllegalArgumentException("Cannot convert between different dimensions");
        }
        return quantity.multiply(baseFactor).divide(target.baseFactor, 6, RoundingMode.HALF_UP);
    }
}

