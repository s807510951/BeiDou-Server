-- Quickslot expansion: widen quickslotkeymapped.keymap from BIGINT (8 bytes)
-- to VARBINARY(64) so a 30-slot key layout can be stored.
-- Existing rows are migrated: the BIGINT is rendered big-endian into its 8
-- bytes (matching the old NumberTool packing); the server's
-- QuickslotBinding.normalize() pads slots 9-30 from defaults on load.
-- 小键盘键位扩展

ALTER TABLE quickslotkeymapped ADD COLUMN keymap_new VARBINARY(64) NULL;

UPDATE quickslotkeymapped SET keymap_new = UNHEX(LPAD(HEX(keymap), 16, '0'));

ALTER TABLE quickslotkeymapped DROP COLUMN keymap;

ALTER TABLE quickslotkeymapped CHANGE COLUMN keymap_new keymap VARBINARY(64) NOT NULL;
