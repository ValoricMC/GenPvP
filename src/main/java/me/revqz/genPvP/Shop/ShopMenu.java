package me.revqz.genPvP.Shop;

import java.util.List;

public record ShopMenu(String id, String displayName, List<ShopItem> items, List<ShopButton> buttons, List<SlotButton> slotButtons) {}
