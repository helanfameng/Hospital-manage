SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- V11 moves the multi-valued drug route attribute out of drug_profile.
-- A route is reference data; drug_profile_route is the catalog relationship.
-- order_item.route remains a text snapshot so historical orders do not change
-- when catalog reference data is retired.
CREATE TABLE medication_route (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_medication_route_name UNIQUE (name),
    CONSTRAINT uk_medication_route_order UNIQUE (sort_order),
    CONSTRAINT ck_medication_route_order CHECK (sort_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO medication_route(code, name, sort_order) VALUES
('ORAL', '口服', 10),
('IV_DRIP', '静脉滴注', 20),
('IV_INJECTION', '静脉注射', 30),
('IM', '肌内注射', 40),
('SC', '皮下注射', 50),
('TOPICAL', '外用', 60),
('INHALATION', '吸入', 70),
('EYE_DROP', '滴眼', 80),
('EAR_DROP', '滴耳', 90),
('NASAL', '鼻用', 100),
('OTHER', '其他', 110);

CREATE TABLE drug_profile_route (
    medical_item_id BIGINT NOT NULL,
    route_code VARCHAR(32) NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT pk_drug_profile_route PRIMARY KEY (medical_item_id, route_code),
    CONSTRAINT uk_drug_profile_route_order UNIQUE (medical_item_id, sort_order),
    CONSTRAINT fk_drug_profile_route_profile FOREIGN KEY (medical_item_id)
        REFERENCES drug_profile(medical_item_id),
    CONSTRAINT fk_drug_profile_route_route FOREIGN KEY (route_code)
        REFERENCES medication_route(code),
    CONSTRAINT ck_drug_profile_route_order CHECK (sort_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Do not silently lose a legacy route during normalization. The temporary
-- procedure is dropped in the same migration and does not become application
-- schema; it only turns an unmapped or duplicated CSV token into a migration
-- failure that can be corrected before production rollout.
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_guard_legacy_medication_routes$$
CREATE PROCEDURE sp_guard_legacy_medication_routes()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM drug_profile dp
        WHERE 1 + LENGTH(dp.allowed_routes) - LENGTH(REPLACE(dp.allowed_routes, ',', ''))
              <> (SELECT COUNT(*)
                  FROM medication_route mr
                  WHERE FIND_IN_SET(mr.name, dp.allowed_routes) > 0)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '药品给药途径包含未登记或重复的历史值，请先清理后执行 V11';
    END IF;
END$$
CALL sp_guard_legacy_medication_routes()$$
DROP PROCEDURE sp_guard_legacy_medication_routes$$
DELIMITER ;

-- Preserve the original comma-list display order as the relationship order.
INSERT INTO drug_profile_route(medical_item_id, route_code, sort_order)
SELECT dp.medical_item_id, mr.code,
       FIND_IN_SET(mr.name, dp.allowed_routes)
FROM drug_profile dp
JOIN medication_route mr ON FIND_IN_SET(mr.name, dp.allowed_routes) > 0;

ALTER TABLE drug_profile
    DROP COLUMN allowed_routes;

CREATE INDEX idx_drug_profile_route_route ON drug_profile_route(route_code, medical_item_id);

DELIMITER $$

-- Route identity and meaning are immutable once published. Retiring a route
-- only changes active, so existing catalog relationships and order snapshots
-- remain explainable.
DROP TRIGGER IF EXISTS trg_medication_route_guard_update$$
CREATE TRIGGER trg_medication_route_guard_update
BEFORE UPDATE ON medication_route FOR EACH ROW
BEGIN
    IF NOT (NEW.code <=> OLD.code) OR NOT (NEW.name <=> OLD.name)
       OR NOT (NEW.sort_order <=> OLD.sort_order) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '已发布的给药途径编码、名称和排序不可修改，只能停用';
    END IF;
END$$

DELIMITER ;
