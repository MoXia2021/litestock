package com.litestock.gui;

import com.litestock.LiteStock;
import com.litestock.config.LiteStockConfig;
import com.litestock.render.ChestHighlightRenderer;
import com.litestock.scan.ContainerCache;
import com.litestock.scan.ContainerProbe;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;

public class SearchTextInput extends GuiTextInput {

    public SearchTextInput(int width, String title, String defaultText, net.minecraft.client.gui.screens.Screen parent, IStringConsumer consumer) {
        super(width, "物品查找", defaultText, parent, consumer);
    }

    @Override
    public void initGui() {
        super.initGui();

        // 用反射拿到 super 加的三个按钮（确定/重置/取消），读最后一个的位置
        int btnW = 80;
        int gap = 4;
        int y = this.height / 2 + 22;
        int startX = this.width / 2;

        try {
            // 遍历 this 的所有字段，找 ButtonBase 类型的按钮
            java.util.List<fi.dy.masa.malilib.gui.button.ButtonBase> buttons = new java.util.ArrayList<>();
            Class<?> cls = this.getClass();
            while (cls != null) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(this);
                        if (val instanceof List<?> list) {
                            for (Object o : list) {
                                if (o instanceof fi.dy.masa.malilib.gui.button.ButtonBase b) {
                                    buttons.add(b);
                                }
                            }
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
            if (!buttons.isEmpty()) {
                // 找最右边的按钮
                fi.dy.masa.malilib.gui.button.ButtonBase rightmost = buttons.get(0);
                for (var b : buttons) {
                    if (b.getX() > rightmost.getX()) rightmost = b;
                }
                startX = rightmost.getX() + rightmost.getWidth() + 4;
                y = rightmost.getY();
            }
        } catch (Exception e) {
            LiteStock.LOGGER.error("反射读按钮失败", e);
        }

        ButtonGeneric clearCache = new ButtonGeneric(startX, y, btnW, 20, "重置缓存");
        this.addButton(clearCache, new ClearCacheListener());

        ButtonGeneric startScan = new ButtonGeneric(startX + btnW + gap, y, btnW, 20, "开始扫描");
        this.addButton(startScan, new StartScanListener());
    }

    private static class ClearCacheListener implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(fi.dy.masa.malilib.gui.button.ButtonBase button, int mouseButton) {
            ContainerCache.getInstance().clear();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "[LiteStock] 缓存已清空").withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    private static class StartScanListener implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(fi.dy.masa.malilib.gui.button.ButtonBase button, int mouseButton) {
            Minecraft mc = Minecraft.getInstance();
            List<BlockPos> selected = LiteStockConfig.get().getSelectedContainerPositions();
            if (selected.isEmpty()) return;

            mc.setScreen(null);
            mc.player.sendSystemMessage(Component.literal(
                    "[LiteStock] 开始扫描 " + selected.size() + " 个箱子...").withStyle(ChatFormatting.YELLOW));

            ChestHighlightRenderer renderer = ChestHighlightRenderer.getInstance();
            if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                var serverLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
                mc.getSingleplayerServer().execute(() -> {
                    for (BlockPos pos : selected) {
                        var be = serverLevel.getBlockEntity(pos);
                        if (be instanceof net.minecraft.world.Container container) {
                            java.util.List<net.minecraft.world.item.ItemStack> stacks = new java.util.ArrayList<>();
                            for (int i = 0; i < container.getContainerSize(); i++) {
                                stacks.add(container.getItem(i));
                            }
                            ContainerCache.getInstance().put(pos, stacks);
                        }
                    }
                    mc.execute(() -> {
                        renderer.clearScanningContainers();
                        mc.player.sendSystemMessage(Component.literal(
                                "[LiteStock] 扫描完成，缓存已更新").withStyle(ChatFormatting.GREEN));
                    });
                });
            } else {
                renderer.setScanningContainers(new HashSet<>(selected));
                ContainerProbe probe = ContainerProbe.getInstance();
                probe.stop();
                probe.resetCooldown();
                probe.setProgressCallback(pos -> mc.execute(() -> renderer.removeScanningContainer(pos)));
                probe.startProbe(selected, () -> {
                    for (BlockPos pos : selected) {
                        java.util.List<net.minecraft.world.item.ItemStack> items = probe.getResults().get(pos);
                        if (items != null) ContainerCache.getInstance().put(pos, items);
                    }
                    mc.execute(() -> {
                        renderer.clearScanningContainers();
                        mc.player.sendSystemMessage(Component.literal(
                                "[LiteStock] 扫描完成，缓存已更新").withStyle(ChatFormatting.GREEN));
                    });
                });
            }
        }
    }
}
