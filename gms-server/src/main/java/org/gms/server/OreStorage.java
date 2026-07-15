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
package org.gms.server;

import org.gms.client.Client;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemFactory;
import org.gms.constants.game.GameConstants;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Matze
 *
 * Backs the ore, scroll, chair and mount bags. Each bag is per-CHARACTER (just like the normal
 * inventory): its items live in {@code inventoryitems} keyed by {@code characterid} + {@code type}
 * via the OREBAG/SCROLLBAG/CHAIRBAG/MOUNTBAG {@link ItemFactory} (account=false). There is NO metadata
 * table — capacity is a fixed {@code BAG_SLOTS} (200). The {@code kind} field selects which bag a given
 * instance represents (0=ore, 1=scroll, 2=chair, 3=mount) and hence which factory type it uses.
 */
public class OreStorage {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);

    // Human name per kind, for logging. Index by kind (0..3). No metadata table: like the normal
    // inventory, bag items live in `inventoryitems` keyed by characterid + type (10-13); slots fixed.
    private static final String[] KIND_NAME = {"ore", "scroll", "chair", "mount"};
    private static final int BAG_SLOTS = 200;

    private static ItemFactory factoryFor(int kind) {
        switch (kind) {
            case 1:  return ItemFactory.SCROLLBAG;
            case 2:  return ItemFactory.CHAIRBAG;
            case 3:  return ItemFactory.MOUNTBAG;
            default: return ItemFactory.OREBAG;
        }
    }

    private final int kind;   // 0=ore, 1=scroll, 2=chair, 3=cash
    private final ItemFactory factory;
    private final int id;
    private int currentNpcid;
    private int meso;
    private int slots;   // widened from byte: bag capacity can exceed 127 (up to 200)
    private final Map<InventoryType, List<Item>> typeItems = new HashMap<>();
    private List<Item> items = new LinkedList<>();
    private final Lock lock = new ReentrantLock(true);

    private OreStorage(int kind, int characterId, int slots) {
        this.kind = kind;
        this.factory = factoryFor(kind);
        this.id = characterId;   // bag items are keyed by characterid (ItemFactory account=false)
        this.slots = slots;
        this.meso = 0;
    }

    // Per-CHARACTER bags — no metadata table. Items load straight from `inventoryitems` (type 10-13,
    // keyed by characterid); a new character simply starts with empty bags. Slots are fixed (BAG_SLOTS).
    public static OreStorage loadOreStorage(int characterId)    { return loadFromDB(0, characterId); }
    public static OreStorage loadScrollStorage(int characterId) { return loadFromDB(1, characterId); }
    public static OreStorage loadChairStorage(int characterId)  { return loadFromDB(2, characterId); }
    public static OreStorage loadMountStorage(int characterId)  { return loadFromDB(3, characterId); }

    private static OreStorage loadFromDB(int kind, int characterId) {
        OreStorage ret = new OreStorage(kind, characterId, BAG_SLOTS);
        try {
            // Slot model: place each item at its SAVED position (item.position); if it's out of range or
            // already taken (legacy rows from the old compacted model), fall back to the first free slot
            // -> clean migration + preserves the layout for rows saved by the new model.
            for (Pair<Item, InventoryType> item : ret.factory.loadItems(characterId, false)) {
                ret.placeOnLoad(item.getLeft());
            }
        } catch (SQLException ex) {
            log.error("SQL error loading {} bag for characterId {}", KIND_NAME[kind], characterId, ex);
            throw new RuntimeException(ex);
        }
        return ret;
    }

    public int getSlots() {
        return slots;
    }

    public boolean canGainSlots(int slots) {
        slots += this.slots;
        return slots <= 200;
    }

    public boolean gainSlots(int slots) {
        lock.lock();
        try {
            if (canGainSlots(slots)) {
                slots += this.slots;
                this.slots = slots;
                return true;
            }

            return false;
        } finally {
            lock.unlock();
        }
    }

    public void saveToDB(Connection con) throws SQLException {
        // No metadata table to update (slots are fixed): just persist the items to `inventoryitems`,
        // keyed by characterid via ItemFactory (account=false), each item's slot held in `position`.
        // No catch: EVERY SQLException must propagate to the caller's save transaction so it rolls back
        // (swallowing a failure after the DELETE-all in saveItems would commit a wiped bag).
        List<Pair<Item, InventoryType>> itemsWithType = new ArrayList<>();
        for (Item item : getItems()) {
            itemsWithType.add(new Pair<>(item, item.getInventoryType()));
        }
        factory.saveItems(itemsWithType, id, con);
    }

    public Item getItem(byte slot) {
        lock.lock();
        try {
            if (slot < 0 || slot >= items.size()) { // guard getSlot()==-1 / stale slot index
                return null;
            }
            return items.get(slot);
        } finally {
            lock.unlock();
        }
    }

    public boolean takeOut(Item item) {
        lock.lock();
        try {
            boolean ret = items.remove(item);

            InventoryType type = item.getInventoryType();
            typeItems.put(type, new ArrayList<>(filterItems(type)));

            return ret;
        } finally {
            lock.unlock();
        }
    }

    // First free slot 0..slots-1 not occupied by any item, or -1 if the bag is full. Caller holds lock.
    private int firstFreeSlot() {
        boolean[] used = new boolean[slots];
        for (Item it : items) {
            int p = it.getPosition();
            if (p >= 0 && p < slots) {
                used[p] = true;
            }
        }
        for (int i = 0; i < slots; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }

    // Item currently occupying slot `slot`, or null. Caller holds lock.
    private Item itemAtSlot(int slot) {
        for (Item it : items) {
            if (it.getPosition() == slot) {
                return it;
            }
        }
        return null;
    }

    // Public, locked accessor: the item at bag slot `slot` (position), or null.
    public Item getItemAtSlot(int slot) {
        lock.lock();
        try {
            return itemAtSlot(slot);
        } finally {
            lock.unlock();
        }
    }

    // Load-time placement: keep the saved position if it's valid + free, else first free (migrates
    // legacy rows that carried a stale position from the old compacted model). No lock: single-threaded.
    private void placeOnLoad(Item item) {
        int p = item.getPosition();
        if (p < 0 || p >= slots || itemAtSlot(p) != null) {
            p = firstFreeSlot();
            if (p < 0) {
                return;   // bag already full (shouldn't happen) -> drop overflow rather than collide
            }
        }
        item.setPosition((short) p);
        items.add(item);
    }

    public boolean store(Item item) {
        lock.lock();
        try {
            int slot = firstFreeSlot();
            if (slot < 0) {
                return false;
            }
            item.setPosition((short) slot);
            items.add(item);

            InventoryType type = item.getInventoryType();
            typeItems.put(type, new ArrayList<>(filterItems(type)));

            return true;
        } finally {
            lock.unlock();
        }
    }

    // Deposit `item` at a SPECIFIC bag slot (the slot the player dropped it on). Empty target -> place
    // there exactly; same stackable item -> merge into it (overflow spills to first-free); occupied by a
    // different item -> fall back to storeMerge (merge/first-free). Mirrors how the inventory honors the
    // drop slot. Returns false only if nothing could be stored.
    public boolean storeAt(Item item, int slot, Client c) {
        lock.lock();
        try {
            if (item == null) {
                return false;
            }
            if (slot < 0 || slot >= slots) {
                return storeMerge(item, c);   // invalid target -> normal merge/first-free
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int itemId = item.getItemId();
            boolean stackable = item.getInventoryType() != InventoryType.EQUIP
                    && !ItemConstants.isRechargeable(itemId)
                    && !ii.isPickupRestricted(itemId);
            Item target = itemAtSlot(slot);
            if (target == null) {
                item.setPosition((short) slot);
                items.add(item);
                return true;
            }
            if (stackable && target.getItemId() == itemId && Objects.equals(target.getOwner(), item.getOwner())) {
                short slotMax = ii.getSlotMax(c, itemId);
                int room = slotMax - target.getQuantity();
                if (room >= item.getQuantity()) {
                    target.setQuantity((short) (target.getQuantity() + item.getQuantity()));
                    return true;
                }
                if (room > 0) {
                    target.setQuantity(slotMax);
                    item.setQuantity((short) (item.getQuantity() - room));
                }
                return storeMerge(item, c);   // remainder -> other stacks / first-free
            }
            return storeMerge(item, c);       // occupied by a different item -> no cross-container swap
        } finally {
            lock.unlock();
        }
    }

    // Rearrange WITHIN the bag: move the item at slot `src` onto slot `dst` (the slot the player dropped
    // it on). Mirrors Inventory.move: empty dst -> move there; same stackable item -> merge (overflow
    // stays at src); different item -> swap positions. Returns false only if src is empty / out of range.
    public boolean move(int src, int dst, Client c) {
        lock.lock();
        try {
            if (src == dst) {
                return true;
            }
            if (src < 0 || src >= slots || dst < 0 || dst >= slots) {
                return false;
            }
            Item source = itemAtSlot(src);
            if (source == null) {
                return false;
            }
            Item target = itemAtSlot(dst);
            if (target == null) {
                source.setPosition((short) dst);
                return true;
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int itemId = source.getItemId();
            boolean stackable = source.getInventoryType() != InventoryType.EQUIP
                    && !ItemConstants.isRechargeable(itemId)
                    && !ii.isPickupRestricted(itemId);
            if (stackable && target.getItemId() == itemId && Objects.equals(target.getOwner(), source.getOwner())) {
                short slotMax = ii.getSlotMax(c, itemId);
                int total = source.getQuantity() + target.getQuantity();
                if (total > slotMax) {
                    target.setQuantity(slotMax);
                    source.setQuantity((short) (total - slotMax));   // source keeps the remainder at src
                } else {
                    target.setQuantity((short) total);
                    items.remove(source);                            // fully merged into target
                }
                return true;
            }
            source.setPosition((short) dst);   // different item -> swap the two slots
            target.setPosition((short) src);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // Remove and return the item at bag slot `slot` (leaves the slot empty; no compaction). null if empty.
    public Item takeOutAt(int slot) {
        lock.lock();
        try {
            Item it = itemAtSlot(slot);
            if (it != null) {
                items.remove(it);
                InventoryType type = it.getInventoryType();
                typeItems.put(type, new ArrayList<>(filterItems(type)));
            }
            return it;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores {@code item}, first merging it into existing stacks of the same item id (up to the
     * per-slot max) before consuming a fresh slot - so repeated deposits of the same ore/scroll
     * combine into one stack instead of many separate entries. All-or-nothing: returns false (item
     * untouched) only when the whole quantity can't fit. {@code c} supplies the per-item slot max.
     */
    public boolean storeMerge(Item item, Client c) {
        lock.lock();
        try {
            if (item == null) {
                return false;
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int itemId = item.getItemId();
            boolean stackable = item.getInventoryType() != InventoryType.EQUIP
                    && !ItemConstants.isRechargeable(itemId)
                    && !ii.isPickupRestricted(itemId);

            if (stackable) {
                short slotMax = ii.getSlotMax(c, itemId);   // this core exposes getSlotMax(Client, int)
                int existingRoom = 0;
                for (Item ex : items) {
                    if (ex.getItemId() == itemId && ex.getQuantity() < slotMax
                            && Objects.equals(ex.getOwner(), item.getOwner())) {
                        existingRoom += slotMax - ex.getQuantity();
                    }
                }
                // all-or-nothing: when slots are full, only accept what existing stacks can absorb
                if (isFull() && existingRoom < item.getQuantity()) {
                    return false;
                }
                for (Item ex : items) {
                    if (item.getQuantity() <= 0) {
                        break;
                    }
                    if (ex.getItemId() != itemId || ex.getQuantity() >= slotMax
                            || !Objects.equals(ex.getOwner(), item.getOwner())) {
                        continue;
                    }
                    int move = Math.min(slotMax - ex.getQuantity(), item.getQuantity());
                    ex.setQuantity((short) (ex.getQuantity() + move));
                    item.setQuantity((short) (item.getQuantity() - move));
                }
                if (item.getQuantity() <= 0) {
                    typeItems.put(item.getInventoryType(), new ArrayList<>(filterItems(item.getInventoryType())));
                    return true;
                }
            }

            int slot = firstFreeSlot();
            if (slot < 0) {
                return false;
            }
            item.setPosition((short) slot);
            items.add(item);
            typeItems.put(item.getInventoryType(), new ArrayList<>(filterItems(item.getInventoryType())));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public List<Item> getItems() {
        lock.lock();
        try {
            return new ArrayList<>(items);   // snapshot: callers (saveToDB/filterItems) iterate off-lock
        } finally {
            lock.unlock();
        }
    }

    private List<Item> filterItems(InventoryType type) {
        List<Item> storageItems = getItems();
        List<Item> ret = new LinkedList<>();

        for (Item item : storageItems) {
            if (item.getInventoryType() == type) {
                ret.add(item);
            }
        }
        return ret;
    }

    public byte getSlot(InventoryType type, byte slot) {
        lock.lock();
        try {
            byte ret = 0;
            List<Item> ofType = typeItems.get(type);
            if (ofType == null || slot < 0 || slot >= ofType.size()) { // null after close()/stale slot
                return -1;
            }
            Item target = ofType.get(slot);
            List<Item> storageItems = getItems();
            for (Item item : storageItems) {
                if (item == target) {
                    return ret;
                }
                ret++;
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    public void sendStorage(Client c, int npcId) {
        if (c.getPlayer().getLevel() < 15) {
            c.getPlayer().dropMessage(1, "You may only use the storage once you have reached level 15.");
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        lock.lock();
        try {
            // Consolidate duplicate stacks (e.g. many partial stacks of the same ore) into merged
            // stacks before showing the bag.
            StorageInventory msi = new StorageInventory(c, items);
            msi.mergeItems();
            items = msi.sortItems();

            items.sort((o1, o2) -> {
                if (o1.getInventoryType().getType() < o2.getInventoryType().getType()) {
                    return -1;
                } else if (o1.getInventoryType() == o2.getInventoryType()) {
                    return 0;
                }
                return 1;
            });

            List<Item> storageItems = getItems();
            for (InventoryType type : InventoryType.values()) {
                typeItems.put(type, new ArrayList<>(storageItems));
            }

            currentNpcid = npcId;
            // Native trunk-UI path (dead for the bag window, which replies via bagWindowSnapshot);
            // the native getStorage packet takes a byte slot count, so narrow for compilation.
            c.sendPacket(PacketCreator.getStorage(npcId, (byte) slots, storageItems, meso));
        } finally {
            lock.unlock();
        }
    }

    public void sendStored(Client c, InventoryType type) {
        lock.lock();
        try {
            c.sendPacket(PacketCreator.storeStorage((byte) slots, type, typeItems.get(type)));
        } finally {
            lock.unlock();
        }
    }

    public void sendTakenOut(Client c, InventoryType type) {
        lock.lock();
        try {
            c.sendPacket(PacketCreator.takeOutStorage((byte) slots, type, typeItems.get(type)));
        } finally {
            lock.unlock();
        }
    }

    public void arrangeItems(Client c) {
        lock.lock();
        try {
            StorageInventory msi = new StorageInventory(c, items);
            msi.mergeItems();
            items = msi.sortItems();

            for (InventoryType type : InventoryType.values()) {
                typeItems.put(type, new ArrayList<>(items));
            }

            c.sendPacket(PacketCreator.arrangeStorage((byte) slots, items));
        } finally {
            lock.unlock();
        }
    }

    // Consolidate identical stacks (up to slot-max) + compact, for the custom bag
    // window's "slot merger" button. Same as arrangeItems() but WITHOUT the native
    // storage 'arrange' packet - the bag window handler replies with bagWindowSnapshot.
    public void mergeStacks(Client c) {
        lock.lock();
        try {
            StorageInventory msi = new StorageInventory(c, items);
            msi.mergeItems();
            List<Item> merged = msi.sortItems();
            // Safety net against item loss: consolidating identical stacks can only KEEP or REDUCE the
            // number of entries, never empty a non-empty bag. If it ever returns an empty list for a
            // non-empty bag (e.g. a future regression in StorageInventory), abort the reorganize and keep
            // the current items rather than committing a wipe that the next character save would persist.
            if (merged.isEmpty() && !items.isEmpty()) {
                log.error("[OreStorage] mergeStacks emptied a non-empty {} bag (characterId {}, {} items) - aborting reorg to avoid item loss",
                        KIND_NAME[kind], id, items.size());
                return;
            }
            items = merged;
            // "organize" button: after consolidating, COMPACT positions to 0..N-1 (this is the one
            // action that deliberately re-packs; normal deposit/withdraw/move keep fixed slots + gaps).
            short pos = 0;
            for (Item it : items) {
                it.setPosition(pos++);
            }
            for (InventoryType type : InventoryType.values()) {
                typeItems.put(type, new ArrayList<>(items));
            }
        } finally {
            lock.unlock();
        }
    }

    public int getMeso() {
        return meso;
    }

    public void setMeso(int meso) {
        if (meso < 0) {
            throw new RuntimeException();
        }
        this.meso = meso;
    }

    public void sendMeso(Client c) {
        c.sendPacket(PacketCreator.mesoStorage((byte) slots, meso));
    }

    public int getStoreFee() {
        return 0;
    }

    public int getTakeOutFee() {
        return 0;
    }

    public boolean isFull() {
        lock.lock();
        try {
            return items.size() >= slots;
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            typeItems.clear();
        } finally {
            lock.unlock();
        }
    }
}
