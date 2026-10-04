package com.materialflow.erp.persistence;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotPathMapper {

    @Select("""
            SELECT id, credit_limit AS creditLimit, credit_reserved AS creditReserved,
                   credit_used AS creditUsed, overdue_amount AS overdueAmount, frozen, version
              FROM credit_accounts
             WHERE organization_id = #{organizationId} AND customer_id = #{customerId}
             FOR UPDATE
            """)
    CreditRow lockCredit(@Param("organizationId") UUID organizationId, @Param("customerId") UUID customerId);

    @Update("""
            UPDATE credit_accounts
               SET credit_reserved = #{creditReserved}, credit_used = #{creditUsed},
                   overdue_amount = #{overdueAmount}, version = version + 1, updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateCredit(
            @Param("id") UUID id,
            @Param("creditReserved") BigDecimal creditReserved,
            @Param("creditUsed") BigDecimal creditUsed,
            @Param("overdueAmount") BigDecimal overdueAmount,
            @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT id, on_hand AS onHand, reserved, in_transit AS inTransit, version
              FROM inventory_balances
             WHERE organization_id = #{organizationId} AND warehouse_id = #{warehouseId}
               AND material_id = #{materialId} AND location_id = #{locationId}
             FOR UPDATE
            """)
    InventoryRow lockInventory(
            @Param("organizationId") UUID organizationId,
            @Param("warehouseId") UUID warehouseId,
            @Param("materialId") UUID materialId,
            @Param("locationId") UUID locationId);

    @Update("""
            UPDATE inventory_balances
               SET on_hand = #{onHand}, reserved = #{reserved}, in_transit = #{inTransit},
                   version = version + 1, updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id} AND version = #{expectedVersion}
               AND #{reserved} >= 0 AND #{onHand} >= #{reserved}
            """)
    int updateInventory(
            @Param("id") UUID id,
            @Param("onHand") BigDecimal onHand,
            @Param("reserved") BigDecimal reserved,
            @Param("inTransit") BigDecimal inTransit,
            @Param("expectedVersion") long expectedVersion);

    record CreditRow(
            UUID id,
            BigDecimal creditLimit,
            BigDecimal creditReserved,
            BigDecimal creditUsed,
            BigDecimal overdueAmount,
            boolean frozen,
            long version) {
    }

    record InventoryRow(
            UUID id,
            BigDecimal onHand,
            BigDecimal reserved,
            BigDecimal inTransit,
            long version) {
    }
}

