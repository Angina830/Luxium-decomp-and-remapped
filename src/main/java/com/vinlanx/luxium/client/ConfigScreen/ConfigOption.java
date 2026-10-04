package com.vinlanx.luxium.client.ConfigScreen;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

public final class ConfigOption<T> {
   private final String id;
   private final Component label;
   private final Component description;
   private final ConfigOption.Type type;
   private final ConfigValue<T> value;
   private final double min;
   private final double max;
   private final double step;
   private final String suffix;
   private final List<T> choices;
   private final Function<T, Component> formatter;
   private final Consumer<T> changed;
   private final BooleanSupplier enabled;
   private final boolean reverseSlider;
   private long revision;

   private ConfigOption(
      String id,
      Component label,
      Component description,
      ConfigOption.Type type,
      ConfigValue<T> value,
      double min,
      double max,
      double step,
      String suffix,
      List<T> choices,
      Function<T, Component> formatter,
      Consumer<T> changed,
      BooleanSupplier enabled,
      boolean reverseSlider
   ) {
      this.id = id;
      this.label = label;
      this.description = description;
      this.type = type;
      this.value = value;
      this.min = min;
      this.max = max;
      this.step = step;
      this.suffix = suffix;
      this.choices = choices;
      this.formatter = formatter;
      this.changed = changed;
      this.enabled = enabled;
      this.reverseSlider = reverseSlider;
   }

   public static ConfigOption<Boolean> bool(String id, String labelKey, String tooltipKey, BooleanValue value) {
      return new ConfigOption<>(
         id,
         Component.translatable(labelKey),
         Component.translatable(tooltipKey),
         ConfigOption.Type.BOOLEAN,
         value,
         0.0,
         1.0,
         1.0,
         "",
         List.of(false, true),
         state -> Component.literal(state ? "ON" : "OFF"),
         ignored -> {
         },
         () -> true,
         false
      );
   }

   public static ConfigOption<Integer> integer(String id, String labelKey, String tooltipKey, IntValue value, int min, int max, int step, String suffix) {
      return new ConfigOption<>(
         id,
         Component.translatable(labelKey),
         Component.translatable(tooltipKey),
         ConfigOption.Type.INTEGER,
         value,
         (double)min,
         (double)max,
         (double)step,
         suffix,
         List.of(),
         number -> Component.literal(number + suffix),
         ignored -> {
         },
         () -> true,
         false
      );
   }

   public static ConfigOption<Double> decimal(
      String id, String labelKey, String tooltipKey, DoubleValue value, double min, double max, double step, String suffix
   ) {
      return new ConfigOption<>(
         id,
         Component.translatable(labelKey),
         Component.translatable(tooltipKey),
         ConfigOption.Type.DOUBLE,
         value,
         min,
         max,
         step,
         suffix,
         List.of(),
         number -> Component.literal(formatDecimal(number, step) + suffix),
         ignored -> {
         },
         () -> true,
         false
      );
   }

   public static <E extends Enum<E>> ConfigOption<E> enumeration(
      String id, String labelKey, String tooltipKey, EnumValue<E> value, List<E> choices, Function<E, Component> formatter, Consumer<E> changed
   ) {
      return new ConfigOption<>(
         id,
         Component.translatable(labelKey),
         Component.translatable(tooltipKey),
         ConfigOption.Type.ENUM,
         value,
         0.0,
         0.0,
         0.0,
         "",
         choices,
         formatter,
         changed,
         () -> true,
         false
      );
   }

   public static ConfigOption<Integer> color(String id, String labelKey, String tooltipKey, IntValue value) {
      return new ConfigOption<>(
         id,
         Component.translatable(labelKey),
         Component.translatable(tooltipKey),
         ConfigOption.Type.COLOR,
         value,
         0.0,
         1.6777215E7,
         1.0,
         "",
         List.of(),
         color -> Component.literal(String.format(Locale.ROOT, "#%06X", color & 16777215)),
         ignored -> {
         },
         () -> true,
         false
      );
   }

   public ConfigOption<T> onChanged(Consumer<T> callback) {
      return new ConfigOption<>(
         this.id,
         this.label,
         this.description,
         this.type,
         this.value,
         this.min,
         this.max,
         this.step,
         this.suffix,
         this.choices,
         this.formatter,
         callback,
         this.enabled,
         this.reverseSlider
      );
   }

   public ConfigOption<T> enabledWhen(BooleanSupplier condition) {
      return new ConfigOption<>(
         this.id,
         this.label,
         this.description,
         this.type,
         this.value,
         this.min,
         this.max,
         this.step,
         this.suffix,
         this.choices,
         this.formatter,
         this.changed,
         condition,
         this.reverseSlider
      );
   }

   public ConfigOption<T> reversedSlider() {
      return new ConfigOption<>(
         this.id,
         this.label,
         this.description,
         this.type,
         this.value,
         this.min,
         this.max,
         this.step,
         this.suffix,
         this.choices,
         this.formatter,
         this.changed,
         this.enabled,
         true
      );
   }

   public T get() {
      return (T)this.value.get();
   }

   public void set(T newValue) {
      this.value.set(newValue);
      this.value.save();
      this.changed.accept(newValue);
      this.revision++;
   }

   public void reset() {
      this.set((T)this.value.getDefault());
   }

   public String id() {
      return this.id;
   }

   public Component label() {
      return this.label;
   }

   public Component description() {
      return this.description;
   }

   public ConfigOption.Type type() {
      return this.type;
   }

   public double min() {
      return this.min;
   }

   public double max() {
      return this.max;
   }

   public double step() {
      return this.step;
   }

   public String suffix() {
      return this.suffix;
   }

   public List<T> choices() {
      return this.choices;
   }

   public Component format(T current) {
      return this.formatter.apply(current);
   }

   public boolean isEnabled() {
      return this.enabled.getAsBoolean();
   }

   public boolean isSliderReversed() {
      return this.reverseSlider;
   }

   public long revision() {
      return this.revision;
   }

   private static String formatDecimal(double value, double step) {
      if (step >= 1.0) {
         return String.format(Locale.ROOT, "%.0f", value);
      } else {
         return step >= 0.1 ? String.format(Locale.ROOT, "%.1f", value) : String.format(Locale.ROOT, "%.2f", value);
      }
   }

   public static enum Type {
      BOOLEAN,
      INTEGER,
      DOUBLE,
      ENUM,
      COLOR;
   }
}
