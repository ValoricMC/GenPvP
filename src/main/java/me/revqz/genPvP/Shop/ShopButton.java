package me.revqz.genPvP.Shop;

import org.bukkit.Material;

import java.util.List;

public record ShopButton(int slot, Material material, String name, List<String> lore, String targetMenu) {}
