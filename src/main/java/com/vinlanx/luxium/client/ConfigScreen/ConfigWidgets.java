package com.vinlanx.luxium.client.ConfigScreen;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

final class ConfigWidgets {
   static final int TEXT = -461080;
   static final int MUTED = -6249040;
   static final int ACCENT = -11157;
   static final int ON = -650067881;
   static final int OFF = -637906350;
   static final int BORDER = 1073741823;

   private ConfigWidgets() {
   }

   static EditBox colorEditor(int x, int y, int width, ConfigOption<Integer> option) {
      EditBox box = new EditBox(Minecraft.getInstance().font, x, y, width, 20, option.label());
      box.setMaxLength(7);
      box.setValue(String.format(Locale.ROOT, "#%06X", option.get() & 16777215));
      box.setFilter(text -> text.matches("#?[0-9a-fA-F]{0,6}"));
      box.setResponder(text -> {
         String hex = text.startsWith("#") ? text.substring(1) : text;
         if (hex.length() == 6) {
            option.set(Integer.parseInt(hex, 16));
         }
      });
      return box;
   }

   private static float animate(float current, float target, float speed) {
      float next = current + (target - current) * speed;
      return Math.abs(next - target) < 0.001F ? target : next;
   }

   private static int blend(int from, int to, float amount) {
      amount = Mth.clamp(amount, 0.0F, 1.0F);
      int a = Math.round(Mth.lerp(amount, (float)(from >>> 24 & 0xFF), (float)(to >>> 24 & 0xFF)));
      int r = Math.round(Mth.lerp(amount, (float)(from >>> 16 & 0xFF), (float)(to >>> 16 & 0xFF)));
      int g = Math.round(Mth.lerp(amount, (float)(from >>> 8 & 0xFF), (float)(to >>> 8 & 0xFF)));
      int b = Math.round(Mth.lerp(amount, (float)(from & 0xFF), (float)(to & 0xFF)));
      return a << 24 | r << 16 | g << 8 | b;
   }

   static final class ActionButton extends AbstractWidget {
      private final Runnable action;
      private final Integer fillColor;
      private final Integer textColor;
      private float hover;

      ActionButton(int x, int y, int width, Component label, Runnable action) {
         this(x, y, width, label, action, null, null);
      }

      ActionButton(int x, int y, int width, Component label, Runnable action, Integer fillColor, Integer textColor) {
         super(x, y, width, 20, label);
         this.action = action;
         this.fillColor = fillColor;
         this.textColor = textColor;
      }

      public void onClick(double mouseX, double mouseY) {
         this.action.run();
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
         this.hover = ConfigWidgets.animate(this.hover, this.isHovered() ? 1.0F : 0.0F, 0.22F);
         if (this.fillColor != null) {
            int fill = ConfigWidgets.blend(this.fillColor, blendLighter(this.fillColor, this.hover * 0.25F), this.hover);
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), fill);
            graphics.drawCenteredString(
               Minecraft.getInstance().font,
               this.getMessage(),
               this.getX() + this.getWidth() / 2,
               this.getY() + 6,
               this.textColor != null ? this.textColor : -461080
            );
         } else {
            int border = ConfigWidgets.blend(1073741823, -11157, this.hover);
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), -1728053248);
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, border);
            graphics.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), border);
            graphics.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.getHeight(), border);
            graphics.fill(this.getX() + this.getWidth() - 1, this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), border);
            graphics.drawCenteredString(
               Minecraft.getInstance().font,
               this.getMessage(),
               this.getX() + this.getWidth() / 2,
               this.getY() + 6,
               ConfigWidgets.blend(-6249040, -461080, this.hover)
            );
         }
      }

      protected void updateWidgetNarration(NarrationElementOutput output) {
         this.defaultButtonNarrationText(output);
      }

      private static int blendLighter(int color, float amount) {
         int a = color >>> 24 & 0xFF;
         int r = Math.min(255, Math.round((float)(color >>> 16 & 0xFF) * (1.0F + amount)));
         int g = Math.min(255, Math.round((float)(color >>> 8 & 0xFF) * (1.0F + amount)));
         int b = Math.min(255, Math.round((float)(color & 0xFF) * (1.0F + amount)));
         return a << 24 | r << 16 | g << 8 | b;
      }
   }

   static final class EnumCycle<E extends Enum<E>> extends AbstractWidget {
      private final ConfigOption<E> option;
      private E value;
      private float hover;
      private float pulse;

      EnumCycle(int x, int y, int width, ConfigOption<E> option) {
         super(x, y, width, 20, option.label());
         this.option = option;
         this.value = option.get();
      }

      public void onClick(double mouseX, double mouseY) {
         int index = this.option.choices().indexOf(this.value);
         this.value = this.option.choices().get((index + 1) % this.option.choices().size());
         this.option.set(this.value);
         this.pulse = 1.0F;
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
         this.hover = ConfigWidgets.animate(this.hover, this.isHovered() ? 1.0F : 0.0F, 0.2F);
         this.pulse = ConfigWidgets.animate(this.pulse, 0.0F, 0.12F);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), -1291845632);
         int border = ConfigWidgets.blend(1073741823, -11157, Math.max(this.hover, this.pulse));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, border);
         graphics.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), border);
         if (this.pulse > 0.01F) {
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), (int)(this.pulse * 34.0F) << 24 | 16766059);
         }

         graphics.drawCenteredString(
            Minecraft.getInstance().font,
            this.option.format(this.value).getString().toUpperCase(Locale.ROOT),
            this.getX() + this.getWidth() / 2,
            this.getY() + 6,
            -461080
         );
      }

      protected void updateWidgetNarration(NarrationElementOutput output) {
         this.defaultButtonNarrationText(output);
      }
   }

   static final class Slider<T extends Number> extends AbstractSliderButton {
      private final ConfigOption<T> option;
      private final boolean integral;
      private float hover;

      Slider(int x, int y, int width, ConfigOption<T> option) {
         super(x, y, width, 20, Component.empty(), normalize(option, option.get().doubleValue()));
         this.option = option;
         this.integral = option.type() == ConfigOption.Type.INTEGER;
         this.updateMessage();
      }

      private static double normalize(ConfigOption<?> option, double current) {
         if ("neo_gpu_vanilla_capture_resolution".equals(option.id())) {
            double power = Math.log(Math.max(1.0, current)) / Math.log(2.0);
            return Mth.clamp(power / 9.0, 0.0, 1.0);
         } else {
            double normalized = Mth.clamp((current - option.min()) / (option.max() - option.min()), 0.0, 1.0);
            return option.isSliderReversed() ? 1.0 - normalized : normalized;
         }
      }

      private double configNormalized(double sliderValue) {
         return this.option.isSliderReversed() ? 1.0 - sliderValue : sliderValue;
      }

      protected void updateMessage() {
         double normalized = this.configNormalized(this.value);
         double raw = this.option.min() + normalized * (this.option.max() - this.option.min());
         double snapped;
         if ("neo_gpu_vanilla_capture_resolution".equals(this.option.id())) {
            snapped = (double)(1 << Mth.clamp((int)Math.round(normalized * 9.0), 0, 9));
         } else {
            snapped = this.option.min() + (double)Math.round((raw - this.option.min()) / this.option.step()) * this.option.step();
         }

         if (this.integral) {
            int integerValue = (int)Math.round(snapped);
            if ("sky_entity_shadow_update_fps".equals(this.option.id()) && integerValue == 61) {
               this.setMessage(Component.literal("MAX"));
            } else {
               this.setMessage(Component.literal(integerValue + this.option.suffix()));
            }
         } else {
            int decimals = this.option.step() >= 1.0 ? 0 : (this.option.step() >= 0.1 ? 1 : 2);
            this.setMessage(Component.literal(String.format(Locale.ROOT, "%." + decimals + "f%s", snapped, this.option.suffix())));
         }
      }

      protected void applyValue() {
         this.applyNormalizedValue(this.value);
      }

      public void onClick(double mouseX, double mouseY) {
         this.setValueFromCursor(mouseX);
      }

      protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
         this.setValueFromCursor(mouseX);
      }

      private void setValueFromCursor(double mouseX) {
         if (this.option.isEnabled()) {
            int trackEnd = this.getX() + this.getWidth() - 52;
            this.value = Mth.clamp((mouseX - (double)this.getX()) / (double)(trackEnd - this.getX()), 0.0, 1.0);
            this.applyNormalizedValue(this.value);
            this.updateMessage();
         }
      }

      private void applyNormalizedValue(double normalizedValue) {
         double configValue = this.configNormalized(normalizedValue);
         double raw = this.option.min() + configValue * (this.option.max() - this.option.min());
         double snapped;
         if ("neo_gpu_vanilla_capture_resolution".equals(this.option.id())) {
            snapped = (double)(1 << Mth.clamp((int)Math.round(configValue * 9.0), 0, 9));
            this.value = normalize(this.option, snapped);
         } else {
            snapped = Mth.clamp(
               this.option.min() + (double)Math.round((raw - this.option.min()) / this.option.step()) * this.option.step(),
               this.option.min(),
               this.option.max()
            );
         }

         if (this.integral) {
            this.setInteger((int)Math.round(snapped));
         } else {
            this.setDecimal(snapped);
         }
      }

      private void setInteger(int value) {
         this.option.set((T)value);
      }

      private void setDecimal(double value) {
         this.option.set((T)value);
      }

      public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
         this.active = this.option.isEnabled();
         this.hover = ConfigWidgets.animate(this.hover, this.active && this.isHovered() ? 1.0F : 0.0F, 0.2F);
         int centerY = this.getY() + this.getHeight() / 2;
         int trackEnd = this.getX() + this.getWidth() - 52;
         int activeColor = this.active ? -11157 : -6249040;
         graphics.fill(this.getX(), centerY - 2, trackEnd, centerY + 2, -1728053248);
         graphics.fill(this.getX(), centerY - 2, this.getX() + (int)((double)(trackEnd - this.getX()) * this.value), centerY + 2, activeColor);
         int handleX = this.getX() + (int)((double)(trackEnd - this.getX()) * this.value);
         int expansion = Math.round(this.hover * 2.0F);
         graphics.fill(
            handleX - 3 - expansion,
            this.getY() + 2 - expansion,
            handleX + 3 + expansion,
            this.getY() + this.getHeight() - 2 + expansion,
            this.active ? ConfigWidgets.blend(-461080, -11157, this.hover) : -6249040
         );
         graphics.drawString(Minecraft.getInstance().font, this.getMessage(), trackEnd + 6, this.getY() + 6, activeColor, true);
      }
   }

   static final class Toggle extends AbstractWidget {
      private final ConfigOption<Boolean> option;
      private boolean value;
      private float selection;
      private float hover;

      Toggle(int x, int y, int width, ConfigOption<Boolean> option) {
         super(x, y, width, 20, option.label());
         this.option = option;
         this.value = option.get();
         this.selection = this.value ? 1.0F : 0.0F;
      }

      public void onClick(double mouseX, double mouseY) {
         if (this.option.isEnabled()) {
            this.value = mouseX >= (double)this.getX() + (double)this.getWidth() / 2.0;
            this.option.set(this.value);
         }
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
         this.active = this.option.isEnabled();
         this.value = this.option.get();
         int half = this.getWidth() / 2;
         this.selection = ConfigWidgets.animate(this.selection, this.value ? 1.0F : 0.0F, 0.24F);
         this.hover = ConfigWidgets.animate(this.hover, this.isHovered() ? 1.0F : 0.0F, 0.22F);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), -1728053248);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, 1073741823);
         graphics.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), 1073741823);
         graphics.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.getHeight(), 1073741823);
         graphics.fill(this.getX() + this.getWidth() - 1, this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), 1073741823);
         int selectionX = this.getX() + Math.round((float)half * this.selection);
         graphics.fill(selectionX, this.getY(), selectionX + half, this.getY() + this.getHeight(), ConfigWidgets.blend(-637906350, -650067881, this.selection));
         if (this.hover > 0.01F) {
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), (int)(this.hover * 20.0F) << 24 | 16777215);
         }

         graphics.drawCenteredString(Minecraft.getInstance().font, "OFF", this.getX() + half / 2, this.getY() + 6, this.value ? -6249040 : -461080);
         graphics.drawCenteredString(Minecraft.getInstance().font, "ON", this.getX() + half + half / 2, this.getY() + 6, this.value ? -461080 : -6249040);
      }

      protected void updateWidgetNarration(NarrationElementOutput output) {
         this.defaultButtonNarrationText(output);
      }
   }
}
