package com.vinlanx.luxium.client.ConfigScreen;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.guiscreen.LuxiumMenuBackgroundRenderer;
import com.vinlanx.luxium.client.shaders.LuxiumGpuShaderFeatures;
import com.vinlanx.luxium.mixin.GameRendererAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

public final class LuxiumConfigScreen extends Screen {
   private static final int PANEL = -1509225201;
   private static final int HOVER = 352321535;
   private static final int BORDER = 872415231;
   private static final int ACCENT = -11157;
   private static final int TEXT = -461080;
   private static final int MUTED = -6249040;
   private static final int ROW_HEIGHT = 32;
   private static final int HEADER_HEIGHT = 38;
   private static final int SIDEBAR_FOOTER_HEIGHT = 72;
   private static final long BACKGROUND_TRANSITION_NANOS = 400000000L;
   private final Screen parent;
   private final List<ConfigScreenModel.Folder> folders = ConfigScreenModel.createFolders();
   private final List<ConfigScreenModel.Category> categories = this.folders.stream().flatMap(folder -> folder.categories().stream()).toList();
   private final List<LuxiumConfigScreen.ControlRow> controls = new ArrayList<>();
   private final Map<String, Float> tabHover = new HashMap<>();
   private final Set<String> expandedFolders = new HashSet<>();
   private final GlassPanelRenderer glassRenderer = new GlassPanelRenderer();
   private final LuxiumMenuBackgroundRenderer menuBackgroundRenderer = new LuxiumMenuBackgroundRenderer();
   private boolean loadedOpaqueIceEnabled = (Boolean)Config.CLIENT.opaqueIceEnabled.get();
   private boolean loadedWaterEnabled = (Boolean)Config.CLIENT.waterEnabled.get();
   private boolean loadedReceiverShadersEnabled = LuxiumGpuShaderFeatures.receiverShadersEnabled();
   private boolean loadedLocalReceiverEnabled = LuxiumGpuShaderFeatures.localReceiverEnabled();
   private boolean loadedNeoGpuVanillaReceiverEnabled = LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled();
   private boolean loadedBlockLightTestEnabled = Config.isFeatureEnabled(Config.CLIENT.blockLightTestEnabled);
   private boolean loadedNeoGpuVanillaCutoutEnabled = (Boolean)Config.CLIENT.neoGpuVanillaCutoutEnabled.get();
   private boolean loadedNeoSkyCelestiaEnabled = LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled();
   private boolean loadedSkyEntityShadowsEnabled = (Boolean)Config.CLIENT.skyEntityShadowsEnabled.get();
   private ConfigScreenModel.Category category;
   private ConfigWidgets.ActionButton resetPageButton;
   private ConfigWidgets.ActionButton resetAllButton;
   private ConfigWidgets.ActionButton youTubeButton;
   private ConfigOption<?> hoveredOption;
   private ConfigScreenModel.Folder hoveredFolder;
   private ConfigScreenModel.Category hoveredCategory;
   private ConfigOption<?> previewOption;
   private boolean previewAfter;
   private long observedPreviewRevision;
   private double scroll;
   private double targetScroll;
   private double sidebarScroll;
   private double sidebarTargetScroll;
   private boolean blurEnabled = true;
   private float blurSelection = 1.0F;
   private boolean dimmingEnabled = true;
   private float dimmingSelection = 1.0F;
   private long lastBackgroundTransitionNanos = System.nanoTime();
   private float previewAnimation = 1.0F;
   private float tabAnimation = 1.0F;
   private float activeTabY = Float.NaN;
   private int panelX;
   private int panelY;
   private int panelWidth;
   private int panelHeight;
   private int sidebarWidth;
   private int previewWidth;
   private boolean compactLayout;

   public LuxiumConfigScreen(Screen parent) {
      super(Component.translatable("title.luxium.config"));
      this.parent = parent;
      this.category = this.categories.get(0);
   }

   protected void init() {
      this.calculateLayout();
      this.buildControls();
   }

   private void calculateLayout() {
      this.compactLayout = this.width < 520 || this.height < 380;
      int horizontalMargin = this.compactLayout ? 8 : 24;
      int verticalMargin = this.compactLayout ? 40 : 72;
      this.panelWidth = Math.min(1400, Math.max(1, this.width - horizontalMargin));
      this.panelHeight = Math.min(820, Math.max(1, this.height - verticalMargin));
      this.panelX = (this.width - this.panelWidth) / 2;
      this.panelY = Math.max(34, (this.height - this.panelHeight) / 2 + (this.compactLayout ? 8 : 12));
      this.sidebarWidth = this.compactLayout ? Mth.clamp(this.panelWidth * 30 / 100, 86, 126) : Mth.clamp(this.panelWidth * 19 / 100, 138, 280);
      this.previewWidth = this.compactLayout ? 0 : Mth.clamp(this.panelWidth * 27 / 100, 190, 380);
   }

   private void buildControls() {
      this.clearWidgets();
      this.controls.clear();
      int controlWidth = this.compactLayout
         ? Mth.clamp((this.panelWidth - this.sidebarWidth) * 44 / 100, 76, 116)
         : Math.max(110, (this.panelWidth - this.sidebarWidth - this.previewWidth - 80) / 2);
      int y = this.contentTop();

      for (ConfigScreenModel.Row row : this.category.rows()) {
         if (row.isHeader()) {
            this.controls.add(new LuxiumConfigScreen.ControlRow(row, null, y));
            y += 38;
         } else {
            ConfigOption<?> option = row.option();
            AbstractWidget widget = this.createWidget(option, controlWidth);
            this.addWidget(widget);
            this.controls.add(new LuxiumConfigScreen.ControlRow(row, widget, y));
            y += 32;
         }
      }

      this.resetPageButton = (ConfigWidgets.ActionButton)this.addWidget(
         new ConfigWidgets.ActionButton(0, 0, 104, Component.literal("RESET PAGE"), this::resetPage)
      );
      this.resetAllButton = (ConfigWidgets.ActionButton)this.addWidget(new ConfigWidgets.ActionButton(0, 0, 94, Component.literal("RESET ALL"), this::resetAll));
      this.youTubeButton = (ConfigWidgets.ActionButton)this.addWidget(
         new ConfigWidgets.ActionButton(0, 0, 96, Component.literal("YouTube"), this::openYouTube, -5438680, -1)
      );
      this.scroll = 0.0;
      this.targetScroll = 0.0;
      this.tabAnimation = 0.0F;
   }

   private AbstractWidget createWidget(ConfigOption<?> option, int width) {
      return (AbstractWidget)(switch (option.type()) {
         case BOOLEAN -> new ConfigWidgets.Toggle(0, 0, Math.min(width, 116), (ConfigOption<Boolean>)option);
         case INTEGER -> new ConfigWidgets.Slider(0, 0, width, option);
         case DOUBLE -> new ConfigWidgets.Slider(0, 0, width, option);
         case ENUM -> new ConfigWidgets.EnumCycle(0, 0, Math.min(width, 150), option);
         case COLOR -> ConfigWidgets.colorEditor(0, 0, Math.min(width, 112), (ConfigOption<Integer>)option);
      });
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.scroll = this.scroll + (this.targetScroll - this.scroll) * 0.28;
      this.sidebarScroll = this.sidebarScroll + (this.sidebarTargetScroll - this.sidebarScroll) * 0.28;
      this.tabAnimation = this.tabAnimation + (1.0F - this.tabAnimation) * 0.22F;
      long now = System.nanoTime();
      float transitionStep = Math.min(1.0F, (float)(now - this.lastBackgroundTransitionNanos) / 4.0E8F);
      this.lastBackgroundTransitionNanos = now;
      this.blurSelection = moveTowards(this.blurSelection, this.blurEnabled ? 1.0F : 0.0F, transitionStep);
      this.dimmingSelection = moveTowards(this.dimmingSelection, this.dimmingEnabled ? 1.0F : 0.0F, transitionStep);
      if (this.minecraft.level == null) {
         this.menuBackgroundRenderer.render(graphics, this.width, this.height, mouseX, mouseY, partialTick);
      }

      if (!this.glassRenderer
            .render(this.panelX, this.panelY, this.panelWidth, this.panelHeight, this.width, this.height, this.blurSelection, this.dimmingSelection)
         && this.dimmingSelection > 0.0F) {
         graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, this.dimmedColor(-1509225201));
      }

      drawBorder(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, 872415231);
      this.renderTitle(graphics, mouseX, mouseY, partialTick);
      this.renderSidebar(graphics, mouseX, mouseY);
      this.renderContent(graphics, mouseX, mouseY, partialTick);
      this.renderPreview(graphics, mouseX, mouseY);
      this.renderCompactPreviewTooltip(graphics, mouseX, mouseY);
   }

   private void renderTitle(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      int youtubeWidth = this.compactLayout ? 72 : 96;
      this.youTubeButton.setWidth(youtubeWidth);
      this.youTubeButton.setX(this.panelX);
      this.youTubeButton.setY(this.panelY - 29);
      this.youTubeButton.visible = true;
      this.youTubeButton.render(graphics, mouseX, mouseY, partialTick);
   }

   private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
      graphics.fill(this.panelX, this.panelY, this.panelX + this.sidebarWidth, this.panelY + this.panelHeight, this.dimmedColor(1291845632));
      graphics.fill(this.panelX + this.sidebarWidth - 1, this.panelY, this.panelX + this.sidebarWidth + 1, this.panelY + this.panelHeight, 872415231);
      int top = this.panelY + 8;
      int bottom = this.panelY + this.panelHeight - 72;
      int tabHeight = this.sidebarTabHeight();
      int y = top - (int)this.sidebarScroll;
      this.hoveredFolder = null;
      this.hoveredCategory = null;
      graphics.enableScissor(this.panelX, top, this.panelX + this.sidebarWidth, bottom);

      for (LuxiumConfigScreen.SidebarEntry entry : this.sidebarEntries()) {
         if (entry.isFolder()) {
            ConfigScreenModel.Folder folder = entry.folder();
            boolean expanded = this.expandedFolders.contains(folder.id());
            boolean hovered = mouseY >= top && mouseY < bottom && inside((double)mouseX, (double)mouseY, this.panelX, y, this.sidebarWidth, tabHeight);
            if (hovered) {
               this.hoveredFolder = folder;
            }

            float hover = this.tabHover.getOrDefault(folder.id(), 0.0F);
            hover += ((hovered ? 1.0F : 0.0F) - hover) * 0.22F;
            this.tabHover.put(folder.id(), hover);
            if (hover > 0.01F) {
               graphics.fill(this.panelX, y, this.panelX + this.sidebarWidth, y + tabHeight, Math.round(20.0F * hover) << 24 | 16777215);
            }

            graphics.drawString(this.font, expanded ? "▼" : "▶", this.panelX + 10, y + (tabHeight - 8) / 2, -11157, true);
            graphics.drawString(
               this.font,
               this.fittedUpper(folder.title(), this.sidebarWidth - 36),
               this.panelX + 24,
               y + (tabHeight - 8) / 2,
               blend(-11157, -461080, hover),
               true
            );
            y += tabHeight;
         } else {
            ConfigScreenModel.Category item = entry.category();
            boolean active = item == this.category;
            boolean hoveredx = mouseY >= top && mouseY < bottom && inside((double)mouseX, (double)mouseY, this.panelX, y, this.sidebarWidth, tabHeight);
            if (hoveredx && !item.description().getString().isEmpty()) {
               this.hoveredCategory = item;
            }

            float hover = this.tabHover.getOrDefault(item.id(), 0.0F);
            hover += ((hoveredx ? 1.0F : 0.0F) - hover) * 0.22F;
            this.tabHover.put(item.id(), hover);
            if (active || hover > 0.01F) {
               int alpha = active ? 20 : Math.round(20.0F * hover);
               graphics.fill(this.panelX, y, this.panelX + this.sidebarWidth, y + tabHeight, alpha << 24 | 16777215);
            }

            if (active) {
               if (Float.isNaN(this.activeTabY)) {
                  this.activeTabY = (float)y;
               }

               this.activeTabY = this.activeTabY + ((float)y - this.activeTabY) * 0.26F;
            }

            int indent = entry.indented() ? 12 : 0;
            Component title = this.fittedUpper(item.title(), this.sidebarWidth - 24 - indent);
            graphics.drawString(
               this.font,
               title,
               this.panelX + 10 + indent + Math.round(3.0F * hover),
               y + (tabHeight - 8) / 2,
               active ? -461080 : blend(-6249040, -461080, hover),
               true
            );
            y += tabHeight;
         }
      }

      if (!Float.isNaN(this.activeTabY)) {
         graphics.fill(this.panelX, Math.round(this.activeTabY), this.panelX + 4, Math.round(this.activeTabY) + tabHeight, -11157);
      }

      graphics.disableScissor();
      int contentHeight = this.sidebarEntries().size() * tabHeight;
      int viewport = bottom - top;
      if (contentHeight > viewport) {
         int thumbHeight = Math.max(20, viewport * viewport / contentHeight);
         int thumbY = top + (int)((double)(viewport - thumbHeight) * this.sidebarScroll / (double)(contentHeight - viewport));
         graphics.fill(this.panelX + this.sidebarWidth - 6, top, this.panelX + this.sidebarWidth - 3, bottom, 855638016);
         graphics.fill(this.panelX + this.sidebarWidth - 6, thumbY, this.panelX + this.sidebarWidth - 3, thumbY + thumbHeight, 1442840575);
      }

      int footerY = this.panelY + this.panelHeight - 72;
      graphics.fill(this.panelX, footerY, this.panelX + this.sidebarWidth, this.panelY + this.panelHeight, this.dimmedColor(Integer.MIN_VALUE));
      graphics.fill(this.panelX, footerY, this.panelX + this.sidebarWidth, footerY + 1, 872415231);
      if (this.sidebarWidth >= 118) {
         graphics.drawString(this.font, Component.translatable("screen.luxium.config.bg_blur"), this.panelX + 8, footerY + 13, -6249040, true);
         graphics.drawString(this.font, Component.translatable("screen.luxium.config.bg_dimming"), this.panelX + 8, footerY + 43, -6249040, true);
      }

      int toggleWidth = this.compactLayout ? 58 : 64;
      int toggleX = this.panelX + this.sidebarWidth - toggleWidth - 8;
      drawToggle(graphics, toggleX, footerY + 7, toggleWidth, 20, this.blurEnabled ? 1.0F : 0.0F);
      drawToggle(graphics, toggleX, footerY + 37, toggleWidth, 20, this.dimmingEnabled ? 1.0F : 0.0F);
   }

   private void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      int left = this.panelX + this.sidebarWidth + 1;
      int right = this.panelX + this.panelWidth - this.previewWidth;
      int top = this.contentTop();
      int bottom = this.panelY + this.panelHeight - 48;
      this.hoveredOption = null;
      int labelX = left + 24;
      int controlRight = right - 24;

      for (LuxiumConfigScreen.ControlRow control : this.controls) {
         if (control.widget != null) {
            control.widget.visible = false;
         }
      }

      graphics.enableScissor(left, top, right, bottom);

      for (LuxiumConfigScreen.ControlRow controlx : this.controls) {
         int animatedOffset = Math.round((1.0F - this.tabAnimation) * 10.0F);
         int y = controlx.baseY - (int)this.scroll + animatedOffset;
         if (controlx.row.isHeader()) {
            if (y + 38 > top && y < bottom) {
               graphics.drawString(this.font, upper(controlx.row.header()), labelX, y + 12, -11157, true);
               graphics.fill(labelX, y + 27, controlRight, y + 29, -11157);
            }
         } else {
            boolean visible = y + 32 > top && y < bottom;
            controlx.widget.visible = visible;
            if (visible) {
               ConfigOption<?> option = controlx.row.option();
               controlx.widget.active = option.isEnabled();
               boolean hovered = inside((double)mouseX, (double)mouseY, left, y, right - left, 32);
               if (hovered) {
                  graphics.fill(left, y, right, y + 32, 352321535);
                  graphics.fill(left, y, left + 2, y + 32, -11157);
                  this.hoveredOption = option;
               }

               graphics.fill(left + 10, y + 32 - 1, right - 10, y + 32, 234881023);
               controlx.widget.setX(controlRight - controlx.widget.getWidth());
               controlx.widget.setY(y + 6);
               int labelWidth = Math.max(20, controlx.widget.getX() - labelX - 6);
               graphics.drawString(this.font, this.fittedUpper(option.label(), labelWidth), labelX, y + 12, option.isEnabled() ? -461080 : -6249040, true);
               controlx.widget.render(graphics, mouseX, mouseY, partialTick);
               if (option.type() == ConfigOption.Type.COLOR) {
                  AbstractWidget hex = controlx.widget;
                  if (hex instanceof EditBox) {
                     EditBox box = (EditBox)hex;

                     try {
                        String hexx = box.getValue().replace("#", "");
                        int color = hexx.length() == 6 ? Integer.parseInt(hexx, 16) : 0;
                        graphics.fill(box.getX() - 28, y + 7, box.getX() - 6, y + 25, 0xFF000000 | color);
                        drawBorder(graphics, box.getX() - 28, y + 7, 22, 18, 872415231);
                     } catch (NumberFormatException var22) {
                     }
                  }
               }
            }
         }
      }

      graphics.disableScissor();
      if (this.hoveredOption != null && this.hoveredOption != this.previewOption) {
         this.previewOption = this.hoveredOption;
         this.syncPreviewWithConfig();
         this.previewAnimation = 0.0F;
      }

      int contentHeight = this.controls.isEmpty() ? 0 : this.controls.get(this.controls.size() - 1).baseY + 32 - top;
      int viewport = bottom - top;
      if (contentHeight > viewport) {
         int thumbHeight = Math.max(24, viewport * viewport / contentHeight);
         int thumbY = top + (int)((double)(viewport - thumbHeight) * this.scroll / (double)(contentHeight - viewport));
         graphics.fill(right - 5, top, right - 2, top + viewport, 855638016);
         graphics.fill(right - 5, thumbY, right - 2, thumbY + thumbHeight, 1442840575);
      }

      int actionsY = this.panelY + this.panelHeight - 34;
      graphics.fill(left, actionsY - 8, right, this.panelY + this.panelHeight, this.dimmedColor(Integer.MIN_VALUE));
      graphics.fill(left, actionsY - 8, right, actionsY - 7, 872415231);
      this.resetAllButton.setX(right - this.resetAllButton.getWidth());
      this.resetAllButton.setY(actionsY);
      this.resetPageButton.setX(this.resetAllButton.getX() - this.resetPageButton.getWidth() - 8);
      this.resetPageButton.setY(actionsY);
      this.resetPageButton.visible = true;
      this.resetAllButton.visible = true;
      this.resetPageButton.render(graphics, mouseX, mouseY, partialTick);
      this.resetAllButton.render(graphics, mouseX, mouseY, partialTick);
   }

   private void renderPreview(GuiGraphics graphics, int mouseX, int mouseY) {
      if (this.previewWidth > 0) {
         int x = this.panelX + this.panelWidth - this.previewWidth;
         graphics.fill(x, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, this.dimmedColor(1711276032));
         graphics.fill(x, this.panelY, x + 2, this.panelY + this.panelHeight, 872415231);
         int left = x + Math.max(14, this.previewWidth / 17);
         int usable = this.previewWidth - Math.max(28, this.previewWidth / 8);
         if (this.hoveredFolder != null) {
            graphics.drawString(this.font, upper(this.hoveredFolder.title()), left, this.panelY + 28, -11157, true);
            graphics.fill(left, this.panelY + 46, left + usable, this.panelY + 48, 452984831);
            int textY = this.panelY + 62;

            for (FormattedCharSequence line : this.font.split(this.hoveredFolder.description(), usable)) {
               graphics.drawString(this.font, line, left, textY, -6249040, true);
               textY += 12;
            }
         } else if (this.hoveredCategory != null) {
            graphics.drawString(this.font, upper(this.hoveredCategory.title()), left, this.panelY + 28, -11157, true);
            graphics.fill(left, this.panelY + 46, left + usable, this.panelY + 48, 452984831);
            int textY = this.panelY + 62;

            for (FormattedCharSequence line : this.font.split(this.hoveredCategory.description(), usable)) {
               graphics.drawString(this.font, line, left, textY, -6249040, true);
               textY += 12;
            }
         } else if (this.previewOption == null) {
            graphics.drawCenteredString(
               this.font, Component.translatable("screen.luxium.config.hover_hint"), x + this.previewWidth / 2, this.panelY + this.panelHeight / 2, -2136955472
            );
         } else {
            this.syncPreviewIfConfigChanged();
            this.previewAnimation = this.previewAnimation + (1.0F - this.previewAnimation) * 0.24F;
            int offsetY = Math.round((1.0F - this.previewAnimation) * 10.0F);
            int alpha = Math.round(this.previewAnimation * 255.0F);
            graphics.drawString(this.font, upper(this.previewOption.label()), left, this.panelY + 28 + offsetY, alpha << 24 | 16766059, true);
            graphics.fill(left, this.panelY + 46, left + usable, this.panelY + 48, 452984831);
            int textY = this.panelY + 62 + offsetY;
            if (ConfigPreviewImages.hasPreview(this.previewOption)) {
               int imageHeight = Math.min(200, usable * 9 / 16);
               ResourceLocation image = ConfigPreviewImages.image(this.previewOption, this.previewAfter);
               graphics.blit(image, left, textY, 0, 0.0F, 0.0F, usable, imageHeight, usable, imageHeight);
               drawBorder(graphics, left, textY, usable, imageHeight, -6249040);
               int buttonWidth = Math.min(112, usable);
               int buttonX = left + (usable - buttonWidth) / 2;
               int buttonY = textY + imageHeight + 5;
               this.drawPreviewModeButton(graphics, buttonX, buttonY, buttonWidth, mouseX, mouseY);
               textY += imageHeight + 28;
            }

            for (FormattedCharSequence line : this.font.split(upper(this.previewOption.description()), usable)) {
               graphics.drawString(this.font, line, left, textY, alpha << 24 | 10528176, true);
               textY += 12;
            }
         }
      }
   }

   private void renderCompactPreviewTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
      if (this.previewWidth <= 0 && this.hoveredOption != null) {
         int popupWidth = Math.max(80, Math.min(320, this.panelWidth - 16));
         int padding = 10;
         int textWidth = popupWidth - padding * 2;
         List<FormattedCharSequence> lines = this.font.split(this.hoveredOption.description(), textWidth);
         boolean hasImage = ConfigPreviewImages.hasPreview(this.hoveredOption);
         int imageHeight = hasImage ? Math.min(150, textWidth * 9 / 16) : 0;
         int popupHeight = padding + 9 + 8 + imageHeight + (imageHeight > 0 ? 28 : 0) + lines.size() * 11 + padding;
         int popupX = mouseX + 14;
         if (popupX + popupWidth > this.width - 4) {
            popupX = mouseX - popupWidth - 14;
         }

         popupX = Mth.clamp(popupX, 4, Math.max(4, this.width - popupWidth - 4));
         int popupY = Mth.clamp(mouseY + 12, 4, Math.max(4, this.height - popupHeight - 4));
         graphics.pose().pushPose();
         graphics.pose().translate(0.0F, 0.0F, 400.0F);

         try {
            graphics.fill(popupX, popupY, popupX + popupWidth, popupY + popupHeight, -16052977);
            drawBorder(graphics, popupX, popupY, popupWidth, popupHeight, -11157);
            int contentX = popupX + padding;
            int contentY = popupY + padding;
            graphics.drawString(this.font, this.fittedUpper(this.hoveredOption.label(), textWidth), contentX, contentY, -11157, true);
            contentY += 17;
            if (imageHeight > 0) {
               this.syncPreviewIfConfigChanged();
               graphics.blit(
                  ConfigPreviewImages.image(this.hoveredOption, this.previewAfter),
                  contentX,
                  contentY,
                  0,
                  0.0F,
                  0.0F,
                  textWidth,
                  imageHeight,
                  textWidth,
                  imageHeight
               );
               drawBorder(graphics, contentX, contentY, textWidth, imageHeight, -6249040);
               contentY += imageHeight + 28;
            }

            for (FormattedCharSequence line : lines) {
               graphics.drawString(this.font, line, contentX, contentY, -6249040, false);
               contentY += 11;
            }
         } finally {
            graphics.pose().popPose();
         }
      }
   }

   private int contentTop() {
      return this.panelY + 18;
   }

   private static float moveTowards(float current, float target, float amount) {
      return current < target ? Math.min(target, current + amount) : Math.max(target, current - amount);
   }

   private int dimmedColor(int color) {
      int alpha = Math.round((float)(color >>> 24 & 0xFF) * this.dimmingSelection);
      return alpha << 24 | color & 16777215;
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (button != 0) {
         return super.mouseClicked(mouseX, mouseY, button);
      } else {
         if (this.previewWidth > 0 && this.previewOption != null && ConfigPreviewImages.hasPreview(this.previewOption)) {
            LuxiumConfigScreen.PreviewImageBounds bounds = this.previewImageBounds();
            int buttonWidth = Math.min(112, bounds.width());
            int buttonX = bounds.x() + (bounds.width() - buttonWidth) / 2;
            int buttonY = bounds.y() + bounds.height() + 5;
            if (inside(mouseX, mouseY, buttonX, buttonY, buttonWidth, 18)) {
               this.previewAfter = !this.previewAfter;
               return true;
            }
         }

         int footerY = this.panelY + this.panelHeight - 72;
         int toggleWidth = this.compactLayout ? 58 : 64;
         int toggleX = this.panelX + this.sidebarWidth - toggleWidth - 8;
         if (inside(mouseX, mouseY, toggleX, footerY + 7, toggleWidth, 20)) {
            this.blurEnabled = mouseX >= (double)toggleX + (double)toggleWidth / 2.0;
            return true;
         } else if (inside(mouseX, mouseY, toggleX, footerY + 37, toggleWidth, 20)) {
            this.dimmingEnabled = mouseX >= (double)toggleX + (double)toggleWidth / 2.0;
            return true;
         } else {
            int tabHeight = this.sidebarTabHeight();
            int sidebarTop = this.panelY + 8;
            int sidebarBottom = this.panelY + this.panelHeight - 72;
            int y = sidebarTop - (int)this.sidebarScroll;

            for (LuxiumConfigScreen.SidebarEntry entry : this.sidebarEntries()) {
               if (mouseY >= (double)sidebarTop && mouseY < (double)sidebarBottom && inside(mouseX, mouseY, this.panelX, y, this.sidebarWidth, tabHeight)) {
                  if (entry.isFolder()) {
                     String id = entry.folder().id();
                     if (!this.expandedFolders.add(id)) {
                        this.expandedFolders.remove(id);
                     }

                     return true;
                  }

                  ConfigScreenModel.Category item = entry.category();
                  this.category = item;
                  this.buildControls();
                  this.previewOption = null;
                  return true;
               }

               y += tabHeight;
            }

            return super.mouseClicked(mouseX, mouseY, button);
         }
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
      int sidebarTop = this.panelY + 8;
      int sidebarBottom = this.panelY + this.panelHeight - 72;
      if (inside(mouseX, mouseY, this.panelX, sidebarTop, this.sidebarWidth, sidebarBottom - sidebarTop)) {
         int contentHeight = this.sidebarEntries().size() * this.sidebarTabHeight();
         int viewport = sidebarBottom - sidebarTop;
         this.sidebarTargetScroll = Mth.clamp(this.sidebarTargetScroll - delta * 28.0, 0.0, (double)Math.max(0, contentHeight - viewport));
         return true;
      } else {
         int left = this.panelX + this.sidebarWidth;
         int right = this.panelX + this.panelWidth - this.previewWidth;
         if (inside(mouseX, mouseY, left, this.panelY, right - left, this.panelHeight)) {
            int contentHeight = this.controls.isEmpty() ? 0 : this.controls.get(this.controls.size() - 1).baseY + 32 - this.contentTop();
            int viewport = this.panelHeight - 66;
            this.targetScroll = Mth.clamp(this.targetScroll - delta * 42.0, 0.0, (double)Math.max(0, contentHeight - viewport));
            return true;
         } else {
            return super.mouseScrolled(mouseX, mouseY, delta);
         }
      }
   }

   public void tick() {
      if (this.minecraft.level == null) {
         this.menuBackgroundRenderer.tick();
      }

      for (LuxiumConfigScreen.ControlRow row : this.controls) {
         if (row.widget instanceof EditBox box) {
            box.tick();
         }
      }
   }

   public void onClose() {
      this.rebuildRendererIfChanged();
      this.minecraft.setScreen(this.parent);
   }

   public void removed() {
      this.rebuildRendererIfChanged();
      this.glassRenderer.close();
   }

   private void rebuildRendererIfChanged() {
      LuxiumGpuShaderFeatures.refreshNow();
      boolean geometryChanged = this.loadedOpaqueIceEnabled != (Boolean)Config.CLIENT.opaqueIceEnabled.get();
      boolean receiverShadersEnabled = LuxiumGpuShaderFeatures.receiverShadersEnabled();
      boolean waterStateChanged = this.loadedWaterEnabled != (Boolean)Config.CLIENT.waterEnabled.get();
      boolean receiverStateChanged = this.loadedReceiverShadersEnabled != receiverShadersEnabled;
      boolean localReceiverStateChanged = this.loadedLocalReceiverEnabled != LuxiumGpuShaderFeatures.localReceiverEnabled();
      boolean neoGpuVanillaReceiverStateChanged = this.loadedNeoGpuVanillaReceiverEnabled != LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled();
      boolean blockLightTestChanged = this.loadedBlockLightTestEnabled != Config.isFeatureEnabled(Config.CLIENT.blockLightTestEnabled);
      boolean neoGpuVanillaCutoutChanged = this.loadedNeoGpuVanillaCutoutEnabled != (Boolean)Config.CLIENT.neoGpuVanillaCutoutEnabled.get();
      boolean neoSkyCelestiaStateChanged = this.loadedNeoSkyCelestiaEnabled != LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled();
      boolean skyEntityShadowPermutationChanged = this.loadedSkyEntityShadowsEnabled != (Boolean)Config.CLIENT.skyEntityShadowsEnabled.get();
      boolean shaderSetChanged = blockLightTestChanged
         || receiverStateChanged
         || localReceiverStateChanged
         || neoGpuVanillaReceiverStateChanged
         || neoSkyCelestiaStateChanged
         || waterStateChanged;
      boolean terrainPermutationChanged = shaderSetChanged
         || LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled() && neoGpuVanillaCutoutChanged
         || (receiverShadersEnabled || LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled()) && skyEntityShadowPermutationChanged;
      if (geometryChanged || terrainPermutationChanged) {
         if (shaderSetChanged) {
            BlockLightTest.clearProgramCache();
         }

         if (this.minecraft.level != null && (geometryChanged || terrainPermutationChanged)) {
            this.minecraft.levelRenderer.allChanged();
         }

         if (shaderSetChanged) {
            ((GameRendererAccessor)this.minecraft.gameRenderer).luxium$reloadShaders(this.minecraft.getResourceManager());
         }

         this.loadedOpaqueIceEnabled = (Boolean)Config.CLIENT.opaqueIceEnabled.get();
         this.loadedWaterEnabled = (Boolean)Config.CLIENT.waterEnabled.get();
         this.loadedReceiverShadersEnabled = receiverShadersEnabled;
         this.loadedLocalReceiverEnabled = LuxiumGpuShaderFeatures.localReceiverEnabled();
         this.loadedNeoGpuVanillaReceiverEnabled = LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled();
         this.loadedBlockLightTestEnabled = Config.isFeatureEnabled(Config.CLIENT.blockLightTestEnabled);
         this.loadedNeoGpuVanillaCutoutEnabled = (Boolean)Config.CLIENT.neoGpuVanillaCutoutEnabled.get();
         this.loadedNeoSkyCelestiaEnabled = LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled();
      }

      this.loadedSkyEntityShadowsEnabled = (Boolean)Config.CLIENT.skyEntityShadowsEnabled.get();
   }

   public boolean isPauseScreen() {
      return true;
   }

   private void resetPage() {
      ConfigScreenModel.reset(this.category);
      this.previewOption = null;
      this.buildControls();
   }

   private void resetAll() {
      ConfigScreenModel.resetAll(this.categories);
      this.previewOption = null;
      this.buildControls();
   }

   private void openYouTube() {
      String url = "https://www.youtube.com/@Vinlanx";
      this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
         if (confirmed) {
            Util.getPlatform().openUri("https://www.youtube.com/@Vinlanx");
         }

         this.minecraft.setScreen(this);
      }, "https://www.youtube.com/@Vinlanx", false));
   }

   private static void drawToggle(GuiGraphics graphics, int x, int y, int width, int height, float selection) {
      int half = width / 2;
      graphics.fill(x, y, x + width, y + height, -1728053248);
      int selectionX = x + Math.round((float)half * selection);
      graphics.fill(selectionX, y, selectionX + half, y + height, blend(-637906350, -650067881, selection));
      graphics.drawCenteredString(Minecraft.getInstance().font, "OFF", x + half / 2, y + 6, selection > 0.5F ? -6249040 : -461080);
      graphics.drawCenteredString(Minecraft.getInstance().font, "ON", x + half + half / 2, y + 6, selection > 0.5F ? -461080 : -6249040);
      drawBorder(graphics, x, y, width, height, 872415231);
   }

   private static void drawBorder(GuiGraphics graphics, int x, int y, int width, int height, int color) {
      graphics.fill(x, y, x + width, y + 1, color);
      graphics.fill(x, y + height - 1, x + width, y + height, color);
      graphics.fill(x, y, x + 1, y + height, color);
      graphics.fill(x + width - 1, y, x + width, y + height, color);
   }

   private void drawPreviewModeButton(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
      boolean hovered = inside((double)mouseX, (double)mouseY, x, y, width, 18);
      graphics.fill(x, y, x + width, y + 18, hovered ? 654311423 : -1728053248);
      drawBorder(graphics, x, y, width, 18, hovered ? -11157 : 872415231);
      graphics.drawCenteredString(this.font, this.previewAfter ? "AFTER" : "BEFORE", x + width / 2, y + 5, hovered ? -11157 : -461080);
   }

   private LuxiumConfigScreen.PreviewImageBounds previewImageBounds() {
      int x = this.panelX + this.panelWidth - this.previewWidth;
      int left = x + Math.max(14, this.previewWidth / 17);
      int usable = this.previewWidth - Math.max(28, this.previewWidth / 8);
      int imageHeight = Math.min(200, usable * 9 / 16);
      int imageY = this.panelY + 62 + Math.round((1.0F - this.previewAnimation) * 10.0F);
      return new LuxiumConfigScreen.PreviewImageBounds(left, imageY, usable, imageHeight);
   }

   private void syncPreviewWithConfig() {
      boolean var10001;
      label12: {
         this.observedPreviewRevision = this.previewOption.revision();
         if (this.previewOption.get() instanceof Boolean value && value) {
            var10001 = true;
            break label12;
         }

         var10001 = false;
      }

      this.previewAfter = var10001;
   }

   private void syncPreviewIfConfigChanged() {
      if (ConfigPreviewImages.hasPreview(this.previewOption)) {
         if (this.previewOption.revision() != this.observedPreviewRevision) {
            boolean var10001;
            label17: {
               this.observedPreviewRevision = this.previewOption.revision();
               if (this.previewOption.get() instanceof Boolean value && value) {
                  var10001 = true;
                  break label17;
               }

               var10001 = false;
            }

            this.previewAfter = var10001;
         }
      }
   }

   private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
      return mouseX >= (double)x && mouseX < (double)(x + width) && mouseY >= (double)y && mouseY < (double)(y + height);
   }

   private static Component upper(Component component) {
      return Component.literal(component.getString().toUpperCase(Locale.ROOT));
   }

   private Component fittedUpper(Component component, int maxWidth) {
      String text = component.getString().toUpperCase(Locale.ROOT);
      return this.font.width(text) <= maxWidth
         ? Component.literal(text)
         : Component.literal(this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("…"))) + "…");
   }

   private int sidebarTabHeight() {
      return this.compactLayout ? 24 : 28;
   }

   private List<LuxiumConfigScreen.SidebarEntry> sidebarEntries() {
      List<LuxiumConfigScreen.SidebarEntry> entries = new ArrayList<>();

      for (ConfigScreenModel.Folder folder : this.folders) {
         if ("main".equals(folder.id())) {
            for (ConfigScreenModel.Category item : folder.categories()) {
               entries.add(LuxiumConfigScreen.SidebarEntry.category(item, false));
            }
         } else {
            entries.add(LuxiumConfigScreen.SidebarEntry.folder(folder));
            if (this.expandedFolders.contains(folder.id())) {
               for (ConfigScreenModel.Category item : folder.categories()) {
                  entries.add(LuxiumConfigScreen.SidebarEntry.category(item, true));
               }
            }
         }
      }

      return entries;
   }

   private static int blend(int from, int to, float amount) {
      amount = Mth.clamp(amount, 0.0F, 1.0F);
      int a = Math.round(Mth.lerp(amount, (float)(from >>> 24 & 0xFF), (float)(to >>> 24 & 0xFF)));
      int r = Math.round(Mth.lerp(amount, (float)(from >>> 16 & 0xFF), (float)(to >>> 16 & 0xFF)));
      int g = Math.round(Mth.lerp(amount, (float)(from >>> 8 & 0xFF), (float)(to >>> 8 & 0xFF)));
      int b = Math.round(Mth.lerp(amount, (float)(from & 0xFF), (float)(to & 0xFF)));
      return a << 24 | r << 16 | g << 8 | b;
   }

   private static record ControlRow(ConfigScreenModel.Row row, AbstractWidget widget, int baseY) {
   }

   private static record PreviewImageBounds(int x, int y, int width, int height) {
   }

   private static record SidebarEntry(ConfigScreenModel.Folder folder, ConfigScreenModel.Category category, boolean indented) {
      static LuxiumConfigScreen.SidebarEntry folder(ConfigScreenModel.Folder folder) {
         return new LuxiumConfigScreen.SidebarEntry(folder, null, false);
      }

      static LuxiumConfigScreen.SidebarEntry category(ConfigScreenModel.Category category, boolean indented) {
         return new LuxiumConfigScreen.SidebarEntry(null, category, indented);
      }

      boolean isFolder() {
         return this.folder != null;
      }
   }
}
