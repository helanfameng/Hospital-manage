package com.hospital.inpatient.service;

import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {
    private final AccountGuard guard;
    private final AuditService audit;

    public InventoryService(AccountGuard guard, AuditService audit) {
        this.guard = guard;
        this.audit = audit;
    }

    public List<Map<String, Object>> items(String keyword) {
        String term = keyword == null ? "" : keyword.trim();
        return guard.jdbc.queryForList("""
            SELECT mi.id,mi.item_code,mi.name,mi.specification,mi.dosage_form,mi.unit,
                   mi.stock_quantity,mi.low_stock_threshold,mi.allow_fractional_quantity,
                   CASE WHEN mi.stock_quantity=0 THEN 'OUT_OF_STOCK'
                        WHEN mi.low_stock_threshold IS NOT NULL AND mi.stock_quantity<=mi.low_stock_threshold THEN 'LOW'
                        ELSE 'NORMAL' END stock_status,
                   (SELECT COUNT(*) FROM drug_inventory_transaction dit WHERE dit.medical_item_id=mi.id) transaction_count,
                   (SELECT MAX(dit.created_at) FROM drug_inventory_transaction dit WHERE dit.medical_item_id=mi.id) last_transaction_at
            FROM medical_item mi
            WHERE mi.active=TRUE AND mi.item_type='DRUG' AND mi.inventory_managed=TRUE
              AND (?='' OR mi.item_code LIKE CONCAT('%',?,'%') OR mi.name LIKE CONCAT('%',?,'%') OR COALESCE(mi.specification,'') LIKE CONCAT('%',?,'%'))
            ORDER BY CASE WHEN mi.stock_quantity=0 THEN 0
                          WHEN mi.low_stock_threshold IS NOT NULL AND mi.stock_quantity<=mi.low_stock_threshold THEN 1
                          ELSE 2 END,mi.item_code
            """, term, term, term, term);
    }

    public PageResult<Map<String, Object>> transactions(long itemId, int page, int size) {
        inventoryItem(itemId, false);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = guard.count("SELECT COUNT(*) FROM drug_inventory_transaction WHERE medical_item_id=?", itemId);
        var rows = guard.jdbc.queryForList("""
            SELECT dit.id,dit.transaction_type,dit.quantity,dit.before_quantity,dit.after_quantity,
                   dit.note,dit.created_at,ua.username operator_username,ua.display_name operator_name
            FROM drug_inventory_transaction dit
            LEFT JOIN user_account ua ON ua.id=dit.operated_by
            WHERE dit.medical_item_id=? ORDER BY dit.id DESC LIMIT ? OFFSET ?
            """, itemId, safeSize, (safePage - 1) * safeSize);
        return new PageResult<>(rows, total, safePage, safeSize);
    }

    @Transactional
    public Map<String, Object> inbound(long itemId, BigDecimal quantity, String note) {
        var item = inventoryItem(itemId, true);
        requireQuantityAllowed(item, quantity, "入库数量");
        BigDecimal before = decimal(item.get("stock_quantity"));
        BigDecimal after = before.add(quantity);
        guard.jdbc.update("UPDATE medical_item SET stock_quantity=?,version=version+1 WHERE id=?", after, itemId);
        long transactionId = insertTransaction(itemId, "INBOUND", quantity, before, after, note.trim());
        audit.success("INVENTORY_INBOUND", "medical_item", itemId,
                Map.of("stockQuantity", before), Map.of("stockQuantity", after, "quantity", quantity, "note", note.trim()));
        return Map.of("transactionId", transactionId, "stockQuantity", after);
    }

    @Transactional
    public Map<String, Object> adjust(long itemId, BigDecimal actualQuantity, String note) {
        var item = inventoryItem(itemId, true);
        requireQuantityAllowed(item, actualQuantity, "盘点数量");
        BigDecimal before = decimal(item.get("stock_quantity"));
        BigDecimal change = actualQuantity.subtract(before);
        if (change.signum() == 0) throw new BusinessException("INVENTORY_UNCHANGED", "盘点数量与当前库存一致，无需调整");
        guard.jdbc.update("UPDATE medical_item SET stock_quantity=?,version=version+1 WHERE id=?", actualQuantity, itemId);
        long transactionId = insertTransaction(itemId, "ADJUSTMENT", change, before, actualQuantity, note.trim());
        audit.success("INVENTORY_ADJUSTMENT", "medical_item", itemId,
                Map.of("stockQuantity", before), Map.of("stockQuantity", actualQuantity, "quantity", change, "note", note.trim()));
        return Map.of("transactionId", transactionId, "stockQuantity", actualQuantity);
    }

    @Transactional
    public Map<String, Object> setThreshold(long itemId, BigDecimal threshold) {
        var item = inventoryItem(itemId, true);
        if (threshold != null) requireQuantityAllowed(item, threshold, "预警阈值");
        Object before = item.get("low_stock_threshold");
        guard.jdbc.update("UPDATE medical_item SET low_stock_threshold=?,version=version+1 WHERE id=?", threshold, itemId);
        audit.success("INVENTORY_THRESHOLD_SET", "medical_item", itemId,
                java.util.Collections.singletonMap("lowStockThreshold", before),
                java.util.Collections.singletonMap("lowStockThreshold", threshold));
        return java.util.Collections.singletonMap("lowStockThreshold", threshold);
    }

    private Map<String, Object> inventoryItem(long itemId, boolean lock) {
        return guard.row("SELECT id,item_code,name,stock_quantity,low_stock_threshold,allow_fractional_quantity FROM medical_item WHERE id=? AND active=TRUE AND item_type='DRUG' AND inventory_managed=TRUE" + (lock ? " FOR UPDATE" : ""), itemId);
    }

    private void requireQuantityAllowed(Map<String, Object> item, BigDecimal quantity, String label) {
        if (quantity == null || quantity.signum() < 0) throw new BusinessException("INVALID_INVENTORY_QUANTITY", label + "不能为负数");
        boolean fractional = Boolean.TRUE.equals(item.get("allow_fractional_quantity")) || "1".equals(String.valueOf(item.get("allow_fractional_quantity")));
        if (!fractional && quantity.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("INVALID_INVENTORY_QUANTITY", "该药品不允许小数库存，" + label + "必须为整数");
        }
    }

    private long insertTransaction(long itemId, String type, BigDecimal quantity, BigDecimal before, BigDecimal after, String note) {
        guard.jdbc.update("INSERT INTO drug_inventory_transaction(medical_item_id,transaction_type,quantity,before_quantity,after_quantity,note,operated_by) VALUES(?,?,?,?,?,?,?)",
                itemId, type, quantity, before, after, note, SecurityUtils.currentUser().id());
        return guard.count("SELECT LAST_INSERT_ID()");
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
