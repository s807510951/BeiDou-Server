INSERT INTO `game_config` (`config_type`, `config_sub_type`, `config_clazz`, `config_code`, `config_value`, `config_desc`) 
VALUES ('server', 'Game Mechanics', 'java.lang.Boolean', 'quest_complete_hp_bonus', 'true', '完成任务是否增加6点最大HP');

INSERT INTO `lang_resources` (`lang_type`, `lang_base`, `lang_code`, `lang_value`) 
VALUES ('zh-CN', 'game_config', 'quest_complete_hp_bonus', '完成任务是否增加6点最大HP');

INSERT INTO `lang_resources` (`lang_type`, `lang_base`, `lang_code`, `lang_value`) 
VALUES ('en-US', 'game_config', 'quest_complete_hp_bonus', 'Whether to gain 6 MaxHP on quest completion');
