/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.net.server.channel.handlers;

import org.apache.commons.lang3.StringUtils;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.id.NpcId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.string.ExtendType;
import org.gms.dao.entity.ExtendValueDO;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.scripting.AbstractPlayerInteraction;
import org.gms.scripting.AbstractScriptManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.server.ItemInformationProvider;
import org.gms.server.ItemInformationProvider.RewardItem;
import org.gms.server.TimerManager;
import org.gms.server.maps.MapleMap;
import org.gms.util.ExtendUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.gms.util.Randomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * @author Jay Estrella
 * @author kevintjuh93
 */

public final class ItemRewardHandler extends AbstractPacketHandler {
    private static final Logger logger = LoggerFactory.getLogger(ItemRewardHandler.class);

    private static ScheduledFuture<?> itemvacTask, mobvacTask, bagTask;
    private static Point position;
    private static MapleMap vacmap;
    private static long timeStart, timeEnd, mvTime;
    private static int MOBVIC_LIMIT = 30; // 每天吸怪限制30分钟

    @Override
    public final void handlePacket(InPacket p, Client c) {
        byte slot = (byte) p.readShort();
        int itemId = p.readInt(); // will load from xml I don't care.

        if (itemId == 2022552 || itemId == 2022615 || itemId == 2022336 || itemId == 2022468) {
            specialHandle(itemId, c);
            return;
        }

        Item it = c.getPlayer().getInventory(InventoryType.USE).getItem(slot);   // null check here thanks to Thora
        if (it == null || it.getItemId() != itemId || c.getPlayer().getInventory(InventoryType.USE).countById(itemId) < 1) {
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Pair<Integer, List<RewardItem>> rewards = ii.getItemReward(itemId);
        for (RewardItem reward : rewards.getRight()) {
            if (!InventoryManipulator.checkSpace(c, reward.itemid, reward.quantity, "")) {
                c.sendPacket(PacketCreator.getShowInventoryFull());
                break;
            }
            if (Randomizer.nextInt(rewards.getLeft()) < reward.prob) {//Is it even possible to get an item with prob 1?
                if (ItemConstants.getInventoryType(reward.itemid) == InventoryType.EQUIP) {
                    final Item item = ii.getEquipById(reward.itemid);
                    if (reward.period != -1) {
                        // TODO is this a bug, meant to be 60 * 60 * 1000?
                        item.setExpiration(currentServerTime() + reward.period * 60 * 60 * 10);
                    }
                    InventoryManipulator.addFromDrop(c, item, false);
                } else {
                    InventoryManipulator.addById(c, reward.itemid, reward.quantity, "", -1);
                }
                InventoryManipulator.removeById(c, InventoryType.USE, itemId, 1, false, false);
                if (reward.worldmsg != null) {
                    String msg = reward.worldmsg;
                    msg = msg.replaceAll("/name", c.getPlayer().getName());
                    msg = msg.replaceAll("/item", ii.getName(reward.itemid));
                    Server.getInstance().broadcastMessage(c.getWorld(), PacketCreator.serverNotice(6, msg));
                }
                break;
            }
        }
        c.sendPacket(PacketCreator.enableActions());
    }

    private void specialHandle(int itemId, Client c) {
        switch (itemId) {
            case 2022552: // 快捷菜单
                NPCScriptManager.getInstance().start(c, NpcId.BEI_DOU_NPC_BASE, null);
                break;
            case 2022336: // 吸怪
                position = ((position == null) ? c.getPlayer().getPosition() : this.position);
                vacmap = c.getPlayer().getMap();
                mobvacTask = runScript(mobvacTask, itemId, "BeiDouSpecial/_mobvac.js", c, 510, "全屏吸怪");
                break;
            case 2022468: // 吸物
                itemvacTask = runScript(itemvacTask, itemId, "BeiDouSpecial/_itemvac.js", c, 500, "全屏捡物");
                break;
            case 2022615: // 矿物/卷轴背包
                bagTask = runScript(bagTask, itemId, "BeiDouSpecial/_organize.js", c, 30000, "矿物/卷轴自动整理");
                break;
            default:
                break;
        }
        c.getAbstractPlayerInteraction().enableActions();
    }

    private ScheduledFuture runScript(ScheduledFuture sf, int itemId, String path, Client c, long time,
                                      String msg) {
        if (sf != null) {
            dispose(itemId, c, c.getPlayer().getId());
            c.getPlayer().dropMessage(0, "[" + msg + "]功能已关闭");
            return null;
        }
        Character player = c.getPlayer();
        c.getPlayer().dropMessage(0, "[" + msg + "]功能已开启");
        if (itemId == 2022336) {
            timeStart = System.currentTimeMillis();
            if (!checkTime(c, player.getId())) {
                c.getPlayer().dropMessage(1, MOBVIC_LIMIT + "分钟吸怪时限已过, 请明天再使用该功能~");
                return null;
            }
            return TimerManager.getInstance().register(() -> {
                try {
                    Invocable invocable = getScriptEngine(path);
                    if(!c.isLoggedIn() || player.getMap() == null)
                        throw new RuntimeException("检测到用户已离线或不在地图中, 吸怪停止~");
                    if ((player.getMap().getId() != vacmap.getId()) ||
                            (player.getMap().getChannelServer().getId() != vacmap.getChannelServer().getId())) {
                        vacmap.resetMapObjects();
                        c.getPlayer().dropMessage(0, "检测到用户已更换地图, 吸怪功能已暂停~");
                        throw new RuntimeException("检测到用户已更换地图, 吸怪功能已暂停~");
                    }
                    mvTime = System.currentTimeMillis() - timeStart;
                    invocable.invokeFunction("start", c.getPlayer(), ItemInformationProvider.getInstance(), position);
                } catch (Exception e) {
                    logger.error("任务异常: " + itemId + (player == null ? ",  用户为空" : e.getMessage()));
                    dispose(itemId, c, player.getId());
                }
            }, time);
        } else {
            return TimerManager.getInstance().register(() -> {
                try {
                    Invocable invocable = getScriptEngine(path);
                    invocable.invokeFunction("start", c.getPlayer(), ItemInformationProvider.getInstance());
                } catch (Exception e) {
                    logger.error("任务异常: " + itemId + e.getMessage());
                    dispose(itemId, c, player.getId());
                }
            }, time);
        }
    }

    private static class SpecialScriptManager extends AbstractScriptManager {
        @Override
        public ScriptEngine getInvocableScriptEngine(String path) {
            return super.getInvocableScriptEngine(path);
        }
    }

    private Invocable getScriptEngine(String path) {
        SpecialScriptManager scriptManager = new SpecialScriptManager();
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(path);
        return (Invocable) scriptEngine;
    }

    private void dispose(int itemId, Client c, Integer playerId) {
        switch (itemId) {
            case 2022336:
                checkTime(c, playerId);
                mobvacTask.cancel(true);
                position = null;
                vacmap = null;
                mobvacTask = null;
                break;
            case 2022468:
                itemvacTask.cancel(true);
                itemvacTask = null;
                break;
            case 2022615:
                bagTask.cancel(true);
                bagTask = null;
                break;
            default:
                break;
        }
    }

    /**
     * 计算吸怪时间
     * @param c
     */
    private boolean checkTime(Client c, Integer playerId) {
        timeEnd = System.currentTimeMillis();
        ExtendValueDO extendValueDO = ExtendUtil.getExtendValue(String.valueOf(playerId),
                ExtendType.CHARACTER_EXTEND_DAILY.getType(), "mobvacLimit");
        String time = extendValueDO == null ? null : extendValueDO.getExtendValue();
        long diff = timeEnd - timeStart;
        mvTime = StringUtils.isBlank(time) ? diff : Long.parseLong(time) + diff;
        ExtendUtil.saveOrUpdateExtendValue(String.valueOf(playerId), ExtendType.CHARACTER_EXTEND_DAILY.getType(),
                "mobvacLimit", String.valueOf(mvTime));
        if (mvTime > MOBVIC_LIMIT * 60 * 1000)  // 限制吸怪30分钟
            return false;
        if (c.getPlayer() != null)
            c.getPlayer().dropMessage(0, "当前已吸怪" + (mvTime/1000/60) + "分钟, 当天剩余可用时间为" + (MOBVIC_LIMIT - mvTime/1000/60) + "分钟");
        return true;
    }
}