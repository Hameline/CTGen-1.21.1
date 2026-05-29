package dev.tocraft.ctgen.impl.screen.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.tocraft.ctgen.data.MapOverlayTextLoader;
import dev.tocraft.ctgen.data.MapWaypoint;
import dev.tocraft.ctgen.data.MapWaypointLoader;
import dev.tocraft.ctgen.impl.network.SyncMapPacket;
import dev.tocraft.ctgen.impl.screen.MapText;
import dev.tocraft.ctgen.rivers.River;
import dev.tocraft.ctgen.rivers.RiverNetworkLoader;
import dev.tocraft.ctgen.roads.Road;
import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.roads.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
@Environment(EnvType.CLIENT)
public class MapWidget extends AbstractWidget {
    private static final float ZOOM_FACTOR = 1.1F;
    private static final int MOVE_SPEED = 10;

    private final Minecraft minecraft;
    private final ResourceLocation mapTexId;

    private final int pixelOffsetX;
    private final int pixelOffsetY;
    private final int mapWidth;
    private final int mapHeight;
    private final double ratio;
    private final int scale;

    private int zoomedWidth = 0;
    private int zoomedHeight = 0;

    private double textureOffsetX = 0;
    private double textureOffsetY = 0;
    private float minZoom;
    private double zoom;

    private boolean showCursorPos;
    private boolean showPlayer;
    private boolean showTexts;
    private final List<MapText> textOverlays = new ArrayList<>();
    private final ResourceLocation mapId;

    @Nullable
    public static MapWidget ofPacket(Minecraft minecraft, int x, int y, int width, int height, @NotNull SyncMapPacket packet) {
        ResourceLocation mapId = packet.getMapId();
        int xOffset = packet.getXOffset();
        int yOffset = packet.getYOffset();
        int mapWidth = packet.getMapWidth();
        int mapHeight = packet.getMapHeight();
        int scale = packet.getScale();
        if (mapId != null) {
            return new MapWidget(minecraft, x, y, width, height, ResourceLocation.fromNamespaceAndPath(mapId.getNamespace(), "textures/gui/" + mapId.getPath() + ".png"), mapId, xOffset, yOffset, mapWidth, mapHeight, scale);
        }
        return null;
    }

    public MapWidget(Minecraft minecraft, int x, int y, int width, int height, ResourceLocation mapTexId, ResourceLocation mapId, int xOffset, int yOffset, int mapWidth, int mapHeight, int scale) {
        this(minecraft, x, y, width, height, mapTexId, mapId, xOffset, yOffset, mapWidth, mapHeight, scale, defaultZoom(width, height, mapWidth, mapHeight), true, true, true);
    }

    @ApiStatus.Internal
    public MapWidget(Minecraft minecraft, int x, int y, int width, int height, ResourceLocation mapTexId, ResourceLocation mapId, int xOffset, int yOffset, int mapWidth, int mapHeight, int scale, float minZoom, boolean showCursorPos, boolean showPlayer, boolean showTexts) {
        super(x, y, width, height, Component.literal("Map Widget"));
        this.minecraft = minecraft;
        this.pixelOffsetX = xOffset;
        this.pixelOffsetY = yOffset;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.ratio = (double) mapWidth / mapHeight;
        this.mapTexId = mapTexId;
        this.scale = scale;
        this.minZoom = minZoom;
        this.zoom = minZoom;
        this.showCursorPos = showCursorPos;
        this.showPlayer = showPlayer;
        this.showTexts = showTexts;
        this.textOverlays.addAll(MapOverlayTextLoader.ENTRIES.getOrDefault(mapId, List.of()));
        this.mapId = mapId;

        updateZoomedWidth();
        updateZoomedHeight();
        resetTextureOffsets();
    }

    public float defaultZoom() {
        return defaultZoom(width, height, mapWidth, mapHeight);
    }

    private static float defaultZoom(int width, int height, int mapWidth, int mapHeight) {
        return Math.max((float) width / mapWidth, (float) height / mapHeight);
    }

    public int getMapWidth() {
        return mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public void setMinZoom(float minZoom) {
        this.minZoom = minZoom;
        if (zoom < minZoom) {
            zoom = minZoom;
        }
    }

    public void setHeight(int height) {
        this.height = height;
        updateZoomedHeight();
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        updateZoomedWidth();
    }

    @Override
    public void setX(int x) {
        super.setX(x);
    }

    @Override
    public void setY(int y) {
        super.setY(y);
    }

    public void setTexts(List<MapText> texts) {
        this.textOverlays.clear();
        this.textOverlays.addAll(texts);
    }

    public ResourceLocation getMapTexId() {
        return mapTexId;
    }

    public double getRatio() {
        return ratio;
    }

    public void resetTextureOffsets() {
        int playerX;
        int playerY;
        if (minecraft.player != null) {
            BlockPos blockPos = minecraft.player.blockPosition();
            int pixelX = (blockPos.getX() / (scale * 4)) + pixelOffsetX;
            int pixelY = (blockPos.getZ() / (scale * 4)) + pixelOffsetY;
            playerX = (int) ((double) pixelX / mapWidth * zoomedWidth);
            playerY = (int) ((double) pixelY / mapHeight * zoomedHeight);
        } else {
            playerX = zoomedWidth / 2;
            playerY = zoomedHeight / 2;
        }

        int tX = playerX - width / 2;
        int tY = playerY - height / 2;

        setTextureOffsetX(tX);
        setTextureOffsetY(tY);
    }

    public void setTextureOffsetX(double textureOffsetX) {
        this.textureOffsetX = textureOffsetX;
        updateZoomedWidth();
    }

    public void setTextureOffsetY(double textureOffsetY) {
        this.textureOffsetY = textureOffsetY;
        updateZoomedHeight();
    }

    public int getTextureY() {
        return (int) (getY() - textureOffsetY);
    }

    public int getTextureX() {
        return (int) (getX() - textureOffsetX);
    }

    public int getZoomedHeight() {
        return zoomedHeight;
    }

    public int getZoomedWidth() {
        return zoomedWidth;
    }

    public void setZoom(double zoom) {
        this.zoom = Math.max(minZoom, zoom);
    }

    public double getZoom() {
        return zoom;
    }

    public double getReadableZoom() {
        return zoom * mapWidth / width;
    }

    private void updateZoomedWidth() {
        zoomedWidth = (int) (mapWidth * zoom);
        if (minZoom >= defaultZoom()) {
            textureOffsetX = Mth.clamp(textureOffsetX, 0, Math.max(0, zoomedWidth - width));
        }
    }

    private void updateZoomedHeight() {
        zoomedHeight = (int) (mapHeight * zoom);
        if (minZoom >= defaultZoom()) {
            textureOffsetY = Mth.clamp(textureOffsetY, 0, Math.max(0, zoomedHeight - height));
        }
    }

    public void setShowCursorPos(boolean showCursorPos) {
        this.showCursorPos = showCursorPos;
    }

    public void setShowPlayer(boolean showPlayer) {
        this.showPlayer = showPlayer;
    }

    public void setShowTexts(boolean showTexts) {
        this.showTexts = showTexts;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics context, int mouseX, int mouseY, float delta) {
        assert minecraft != null && minecraft.player != null;

        updateZoomedWidth();
        updateZoomedHeight();

        context.flush();
        final double scaleFactor = minecraft.getWindow().getGuiScale();
        RenderSystem.enableScissor((int) (getX() * scaleFactor), (int) (getY() * scaleFactor), (int) (width * scaleFactor), (int) (height * scaleFactor));

        context.blit(mapTexId, getTextureX(), getTextureY(), 0, 0, zoomedWidth, zoomedHeight, zoomedWidth, zoomedHeight);

        if (showPlayer) {
            BlockPos blockPos = minecraft.player.blockPosition();
            int pixelX = (blockPos.getX() / (scale * 4)) + pixelOffsetX;
            int pixelY = (blockPos.getZ() / (scale * 4)) + pixelOffsetY;
            int playerX = (int) (getTextureX() + (double) pixelX / mapWidth * zoomedWidth);
            int playerY = (int) (getTextureY() + (double) pixelY / mapHeight * zoomedHeight);

            if (playerX < getTextureX() + 4) playerX = getTextureX() + 4;
            if (playerY < getTextureY() + 4) playerY = getTextureY() + 4;
            if (playerX > getTextureX() - 4 + zoomedWidth) playerX = getTextureX() - 4 + zoomedWidth;
            if (playerY > getTextureY() - 4 + zoomedHeight) playerY = getTextureY() - 4 + zoomedHeight;

            ResourceLocation skin = minecraft.player.getSkin().texture();
            context.blit(skin, playerX - 4, playerY - 4, 0, 8.0f, 8.0f, 8, 8, 64, 64);
            context.blit(skin, playerX - 4, playerY - 4, 0, 40.0f, 8.0f, 8, 8, 64, 64);
        }

        // render river lines — drawn before roads so roads appear on top
        RiverNetworkLoader.getNetwork().ifPresent(network -> {
            for (River river : network.rivers()) {
                if (!river.type().visibleOnMap()) continue;
                if (river.waypoints().size() < 2) continue;
                drawRiverSpline(context, river);
            }
        });

        RoadNetworkLoader.getNetwork().ifPresent(network -> {
            for (Road road : network.roads()) {
                List<Waypoint> waypoints = road.waypoints();
                if (waypoints.size() < 2) continue;
                for (int i = 0; i < waypoints.size() - 1; i++) {
                    Waypoint from = waypoints.get(i);
                    Waypoint to = waypoints.get(i + 1);
                    drawRoadSegment(context, from, to, road.minZoom(), road.maxZoom());
                }
            }
        });

        // render waypoints — keyed by mapId, not player dimension
        // mapId matches the waypoint file name e.g. agotmod:known_world
        for (MapWaypoint waypoint : MapWaypointLoader.getWaypoints(mapId)) {
            renderWaypoint(context, waypoint);
        }

        if (isHovered && showCursorPos) {
            // convert mouse pixel position to world block coordinates
            int mousePixelX = mousePixelX(mouseX);
            int mousePixelY = mousePixelY(mouseY);
            int worldX = (mousePixelX - pixelOffsetX) * scale * 4;
            int worldZ = (mousePixelY - pixelOffsetY) * scale * 4;

            Component textPos = Component.translatable("ctgen.screen.mouse_pos",
                    Component.translatable("ctgen.coordinates", worldX, worldZ));
            Component textZoom = Component.translatable("ctgen.screen.zoom",
                    String.format("%.2f", getReadableZoom()));

            PoseStack pose = context.pose();
            pose.pushPose();
            pose.scale(0.5f, 0.5f, 1f);

            // cursor coordinates — bottom center
            int posWidth = minecraft.font.width(textPos);
            context.drawString(minecraft.font, textPos,
                    (int) (getX() / 0.5f + width / 1.0f - (float) posWidth / 2),
                    (int) ((getY() + (height - (float) height / 8)) / 0.5f),
                    0xFFFFFF);

            // zoom level — top right
            int zoomWidth = minecraft.font.width(textZoom);
            context.drawString(minecraft.font, textZoom,
                    (int) ((getX() + width) / 0.5f - zoomWidth - 4),
                    (int) ((getY() + 4) / 0.5f),
                    0xFFFFFF);

            pose.popPose();
        }

        if (showTexts) {
            double readableZoom = this.getReadableZoom();
            for (MapText entry : this.textOverlays) {
                if (readableZoom > entry.minZoom() && (readableZoom < entry.maxZoom() || entry.maxZoom() == -1)) {
                    float opacity = entry.getOpacity(readableZoom);
                    int alpha = (int) (opacity * 255);
                    if (alpha <= 0) continue;
                    int color = (alpha << 24) | 0xFFFFFF;

                    int px = getTextureX() + (int) (entry.x() * zoom);
                    int py = getTextureY() + (int) (entry.y() * zoom);

                    PoseStack pose = context.pose();
                    pose.pushPose();

                    pose.translate(px, py, 0);
                    pose.mulPose(Axis.ZP.rotationDegrees(entry.rotation()));
                    pose.scale((float) zoom * entry.size(), (float) zoom * entry.size(), 1f);

                    Component text = Component.translatable(entry.text().getString()).withStyle(entry.text().getStyle());
                    context.drawString(minecraft.font, text, 0, 0, color);

                    pose.popPose();
                }
            }
        }

        context.flush();
        RenderSystem.disableScissor();
    }

    /**
     * Converts a world X coordinate to a screen X coordinate on the map widget.
     */
    private int worldToScreenX(int worldX) {
        int pixelX = (worldX / (scale * 4)) + pixelOffsetX;
        return getTextureX() + (int) ((double) pixelX / mapWidth * zoomedWidth);
    }

    /**
     * Converts a world Z coordinate to a screen Y coordinate on the map widget.
     */
    private int worldToScreenY(int worldZ) {
        int pixelZ = (worldZ / (scale * 4)) + pixelOffsetY;
        return getTextureY() + (int) ((double) pixelZ / mapHeight * zoomedHeight);
    }

    /**
     * Draws a dotted line between two waypoints on the map.
     * The dot size and spacing are fixed in screen pixels so they
     * don't scale with zoom.
     */
    private void drawRoadSegment(@NotNull GuiGraphics context, @NotNull Waypoint from, @NotNull Waypoint to, float minZoom, float maxZoom) {
        double readableZoom = getReadableZoom();

        // disappears when zoomed out past minZoom
        // always visible when zooming in — no max zoom cutoff unless explicitly set
        if (readableZoom < minZoom) return;
        if (maxZoom != -1 && readableZoom > maxZoom) return;

        // fade in near minZoom threshold
        float opacity = 1.0f;
        if (minZoom > 0) {
            float fadeRange = minZoom * 0.3f;
            if (readableZoom < minZoom + fadeRange) {
                opacity = (float) ((readableZoom - minZoom) / fadeRange);
            }
        }
        opacity = Math.max(0, Math.min(1, opacity));
        if (opacity <= 0) return;

        int alpha = (int) (opacity * 255);
        int outerColor = (alpha << 24) | 0xAAAAAA;
        int innerColor = (alpha << 24) | 0xFFFFFF;

        double dotSpacing = 6.0;
        double worldDist = Math.sqrt(Math.pow(to.x() - from.x(), 2) + Math.pow(to.z() - from.z(), 2));
        int samples = Math.max(256, (int) (worldDist / 2));

        List<double[]> screenPoints = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;

            double midX = (from.x() + to.x()) / 2.0;
            double midZ = (from.z() + to.z()) / 2.0;
            double dx = to.x() - from.x();
            double dz = to.z() - from.z();
            double len = Math.sqrt(dx * dx + dz * dz);
            double perpX = -dz / len;
            double perpZ = dx / len;
            double controlX = midX + perpX * len * to.curve();
            double controlZ = midZ + perpZ * len * to.curve();

            double wx = (1-t)*(1-t)*from.x() + 2*(1-t)*t*controlX + t*t*to.x();
            double wz = (1-t)*(1-t)*from.z() + 2*(1-t)*t*controlZ + t*t*to.z();

            double pixelX = (wx / (scale * 4)) + pixelOffsetX;
            double pixelZ = (wz / (scale * 4)) + pixelOffsetY;
            double screenX = getTextureX() + pixelX / mapWidth * zoomedWidth;
            double screenY = getTextureY() + pixelZ / mapHeight * zoomedHeight;

            screenPoints.add(new double[]{screenX, screenY});
        }

        double accumulated = 0;
        boolean drawing = true;

        for (int i = 1; i < screenPoints.size(); i++) {
            double[] prev = screenPoints.get(i - 1);
            double[] curr = screenPoints.get(i);

            double segDx = curr[0] - prev[0];
            double segDz = curr[1] - prev[1];
            double segLen = Math.sqrt(segDx * segDx + segDz * segDz);

            if (segLen < 0.0001) continue;

            double walked = 0;
            while (walked < segLen) {
                double remaining = dotSpacing - accumulated;
                if (walked + remaining > segLen) {
                    accumulated += segLen - walked;
                    break;
                }

                walked += remaining;
                accumulated = 0;
                drawing = !drawing;

                if (drawing) {
                    double frac = walked / segLen;
                    int px = (int) Math.round(prev[0] + segDx * frac);
                    int py = (int) Math.round(prev[1] + segDz * frac);

                    if (px >= getX() && px < getX() + width && py >= getY() && py < getY() + height) {
                        context.fill(px - 1, py - 1, px + 2, py + 2, outerColor);
                        context.fill(px, py, px + 1, py + 1, innerColor);
                    }
                }
            }
        }
    }

    private void renderWaypoint(@NotNull GuiGraphics context, @NotNull MapWaypoint waypoint) {
        double readableZoom = getReadableZoom();

        int screenX = worldToScreenX(waypoint.x());
        int screenY = worldToScreenY(waypoint.z());

        if (screenX < getX() || screenX >= getX() + width || screenY < getY() || screenY >= getY() + height) return;

        // --- dot ---
        boolean showDot = readableZoom >= waypoint.minZoom()
                && (waypoint.maxZoom() == -1 || readableZoom <= waypoint.maxZoom());

        if (showDot) {
            double zoomScale = Math.min(readableZoom / Math.max(waypoint.minZoom(), 0.01f), 2.0);
            int outerRadius = (int) Math.round((1 + zoomScale * 0.8) * 0.7);
            int innerRadius = Math.max(1, outerRadius - 1);

            float dotOpacity = 1.0f;
            float fadeRange = Math.max(waypoint.minZoom() * 0.3f, 0.01f);
            if (readableZoom < waypoint.minZoom() + fadeRange) {
                dotOpacity = (float) ((readableZoom - waypoint.minZoom()) / fadeRange);
            }
            if (waypoint.maxZoom() != -1) {
                float fadeOutStart = waypoint.maxZoom() * 0.9f;
                if (readableZoom > fadeOutStart) {
                    dotOpacity = Math.min(dotOpacity, (float) ((waypoint.maxZoom() - readableZoom)
                            / (waypoint.maxZoom() - fadeOutStart)));
                }
            }
            dotOpacity = Math.max(0, Math.min(1, dotOpacity));

            if (dotOpacity > 0) {
                int alpha = (int) (dotOpacity * 255);
                int outerColor = (alpha << 24) | (waypoint.outerColor() & 0x00FFFFFF);
                int innerColor = (alpha << 24) | (waypoint.innerColor() & 0x00FFFFFF);
                drawFilledCircle(context, screenX, screenY, outerRadius, innerRadius, outerColor, innerColor);
            }
        }

        // --- text ---
        boolean showText = readableZoom >= waypoint.textMinZoom()
                && (waypoint.textMaxZoom() == -1 || readableZoom <= waypoint.textMaxZoom());

        if (showText) {
            float textOpacity = 1.0f;
            float fadeRange = Math.max(waypoint.textMinZoom() * 0.3f, 0.01f);
            if (readableZoom < waypoint.textMinZoom() + fadeRange) {
                textOpacity = (float) ((readableZoom - waypoint.textMinZoom()) / fadeRange);
            }
            if (waypoint.textMaxZoom() != -1) {
                float fadeOutStart = waypoint.textMaxZoom() * 0.9f;
                if (readableZoom > fadeOutStart) {
                    textOpacity = Math.min(textOpacity, (float) ((waypoint.textMaxZoom() - readableZoom)
                            / (waypoint.textMaxZoom() - fadeOutStart)));
                }
            }
            textOpacity = Math.max(0, Math.min(1, textOpacity));

            if (textOpacity > 0) {
                int alpha = (int) (textOpacity * 255);
                float nameScale = 0.5f;
                int nameColor = (alpha << 24) | 0xFFFFFF;
                String name = waypoint.name();
                int textWidth = minecraft.font.width(name);

                // dot radius for positioning — use 1 if dot is not shown
                int dotRadius = showDot ? (int) Math.round((1 + Math.min(readableZoom / Math.max(waypoint.minZoom(), 0.01f), 2.0) * 0.8) * 0.7) : 1;

                PoseStack pose = context.pose();
                pose.pushPose();
                pose.scale(nameScale, nameScale, 1f);

                int nameX = (int) ((screenX / nameScale) - textWidth / 2f);
                int nameY = (int) ((screenY + dotRadius + 3) / nameScale);

                int bgAlpha = (int) (textOpacity * 80);
                int bgColor = (bgAlpha << 24) | 0x000000;
                int padding = 2;
                context.fill(
                        nameX - padding,
                        nameY - padding,
                        nameX + textWidth + padding,
                        nameY + minecraft.font.lineHeight + padding,
                        bgColor
                );

                context.drawString(minecraft.font, name, nameX, nameY, nameColor, false);
                pose.popPose();
            }
        }
    }

    private void drawRiverSpline(@NotNull GuiGraphics context, @NotNull River river) {
        int outerColor = 0xFF004499;
        int innerColor = 0xFF0066FF;

        // high resolution sampling — one sample per 2 world blocks of estimated length
        List<dev.tocraft.ctgen.roads.Waypoint> wps = river.waypoints();
        double totalDist = 0;
        for (int i = 0; i < wps.size() - 1; i++) {
            double dx = wps.get(i + 1).x() - wps.get(i).x();
            double dz = wps.get(i + 1).z() - wps.get(i).z();
            totalDist += Math.sqrt(dx * dx + dz * dz);
        }
        int samples = Math.max(256, (int) (totalDist / 2));

        // precompute screen positions in double precision — no integer truncation
        List<double[]> screenPoints = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double[] pos = river.evaluateSpline(t);

            // same double-precision conversion as the road fix
            double pixelX = (pos[0] / (scale * 4)) + pixelOffsetX;
            double pixelZ = (pos[1] / (scale * 4)) + pixelOffsetY;
            double screenX = getTextureX() + pixelX / mapWidth * zoomedWidth;
            double screenY = getTextureY() + pixelZ / mapHeight * zoomedHeight;

            screenPoints.add(new double[]{screenX, screenY});
        }

        // walk at fixed pixel intervals — consistent spacing at any zoom
        double dotSpacing = 3.0; // slightly tighter than roads since rivers are solid lines
        double accumulated = 0;
        boolean drawing = true;

        for (int i = 1; i < screenPoints.size(); i++) {
            double[] prev = screenPoints.get(i - 1);
            double[] curr = screenPoints.get(i);

            double segDx = curr[0] - prev[0];
            double segDz = curr[1] - prev[1];
            double segLen = Math.sqrt(segDx * segDx + segDz * segDz);

            if (segLen < 0.0001) continue;

            double walked = 0;
            while (walked < segLen) {
                double remaining = dotSpacing - accumulated;
                if (walked + remaining > segLen) {
                    accumulated += segLen - walked;
                    break;
                }

                walked += remaining;
                accumulated = 0;
                drawing = !drawing;

                if (drawing) {
                    double frac = walked / segLen;
                    int px = (int) Math.round(prev[0] + segDx * frac);
                    int py = (int) Math.round(prev[1] + segDz * frac);

                    if (px >= getX() && px < getX() + width && py >= getY() && py < getY() + height) {
                        context.fill(px - 1, py - 1, px + 2, py + 2, outerColor);
                        context.fill(px, py, px + 1, py + 1, innerColor);
                    }
                }
            }
        }
    }

    /**
     * Draws a filled circle with an inner color and outer color.
     * Each pixel is colored based on its distance from the center.
     */
    private void drawFilledCircle(@NotNull GuiGraphics context, int cx, int cy, int outerRadius, int innerRadius, int outerColor, int innerColor) {
        for (int px = -outerRadius; px <= outerRadius; px++) {
            for (int py = -outerRadius; py <= outerRadius; py++) {
                double dist = Math.sqrt(px * px + py * py);
                if (dist <= innerRadius) {
                    context.fill(cx + px, cy + py, cx + px + 1, cy + py + 1, innerColor);
                } else if (dist <= outerRadius) {
                    context.fill(cx + px, cy + py, cx + px + 1, cy + py + 1, outerColor);
                }
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean bl = super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_W || keyCode == GLFW.GLFW_KEY_UP) {
            textureOffsetY -= MOVE_SPEED;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S || keyCode == GLFW.GLFW_KEY_DOWN) {
            textureOffsetY += MOVE_SPEED;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A || keyCode == GLFW.GLFW_KEY_LEFT) {
            textureOffsetX -= MOVE_SPEED;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_D || keyCode == GLFW.GLFW_KEY_RIGHT) {
            textureOffsetX += MOVE_SPEED;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET || keyCode == GLFW.GLFW_KEY_KP_ADD) {
            zoom(ZOOM_FACTOR, (double) width / 2, (double) height / 2);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SLASH || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoom(1 / ZOOM_FACTOR, (double) width / 2, (double) height / 2);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        //#if MC>=1214
        if (active && visible && button == 1 && minecraft != null && minecraft.player != null && isMouseOver(mouseX, mouseY)) {
            //#else
            //$$ if (active && visible && button == 1 && minecraft != null && minecraft.player != null && clicked(mouseX, mouseY)) {
            //#endif
            if (isHovered) {
                if (minecraft.player.hasPermissions(2)) {
                    int mousePixelX = mousePixelX(mouseX);
                    int mousePixelY = mousePixelY(mouseY);
                    int worldX = (mousePixelX - pixelOffsetX) * scale * 4;
                    int worldZ = (mousePixelY - pixelOffsetY) * scale * 4;
                    minecraft.player.connection.sendCommand("ctgen teleport " + worldX + " " + worldZ);
                    this.playDownSound(minecraft.getSoundManager());
                    active = false;
                    return true;
                }
            }
        }
        return false;
    }

    public int mousePixelX(double mouseX) {
        return (int) ((mouseX - getTextureX()) / zoomedWidth * mapWidth);
    }

    public int mousePixelY(double mouseY) {
        return (int) ((mouseY - getTextureY()) / zoomedHeight * mapHeight);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        double value;
        if (deltaY > 0) {
            value = ZOOM_FACTOR;
        } else if (deltaY < 0) {
            value = 1 / ZOOM_FACTOR;
        } else {
            value = 1;
        }
        if (isHovered) {
            zoom(value, mouseX - getX(), mouseY - getY());
        } else {
            zoom(value, (double) width / 2, (double) height / 2);
        }
        return true;
    }

    private void zoom(double fac, double relX, double relY) {
        double oZoom = zoom;
        setZoom(zoom * fac);
        if (zoom != oZoom) {
            double newZ = zoom / oZoom;
            textureOffsetX = (textureOffsetX + relX) * newZ - relX;
            textureOffsetY = (textureOffsetY + relY) * newZ - relY;
            updateZoomedHeight();
            updateZoomedWidth();
        }
    }



    @Override
    public void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        textureOffsetX -= dragX;
        textureOffsetY -= dragY;
        updateZoomedWidth();
        updateZoomedHeight();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
