package me.revqz.genPvP.Shop;

import java.util.List;

/**
 * A purely decorative STONE_BUTTON placed at a fixed slot in a shop menu.
 * It cannot be bought, sold, or interacted with — clicking it does nothing.
 */
public record SlotButton(int slot, String name, List<String> lore) {}
