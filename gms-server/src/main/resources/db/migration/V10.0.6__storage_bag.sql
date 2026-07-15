-- Storage Bag (ore/scroll/chair/mount): per-character auto-collect toggles.
-- Items live in `inventoryitems` keyed by `characterid` + `type`:
--   type 10 = ore bag, 11 = scroll bag, 12 = chair bag, 13 = mount bag
-- Capacity is a fixed 200 slots (no metadata table needed).

ALTER TABLE `characters` ADD COLUMN `autoOreStorage`    TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `characters` ADD COLUMN `autoScrollStorage` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `characters` ADD COLUMN `autoChairStorage`  TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `characters` ADD COLUMN `autoMountStorage`  TINYINT(1) NOT NULL DEFAULT 0;

-- Clean up old per-account bag items (characterid IS NULL) if any exist.
DELETE FROM `inventoryitems` WHERE `type` IN (10, 11, 12, 13) AND `characterid` IS NULL;

-- Drop old per-account metadata table if it exists.
DROP TABLE IF EXISTS `orestorages`;
