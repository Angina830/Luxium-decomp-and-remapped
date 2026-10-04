package com.vinlanx.luxium.client.ConfigScreen;

import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.resources.ResourceLocation;

final class ConfigPreviewImages {
   private static final String NAMESPACE = "luxium";
   private static final String DIRECTORY = "configpic/";
   private static final Map<String, ConfigPreviewImages.ImagePair> IMAGES = Map.ofEntries(
      entry("lens_flare_enabled", "cameralensoff.jpg", "cameralenson.jpg"),
      entry("entity_shadows_enabled", "enitirealisticshadowsoff.jpg", "enitirealisticshadowson.jpg"),
      entry("sky_volumetric_god_rays_entity_occlusion", "entityperekrivaeoff.jpg", "entityperekrivaeon.jpg"),
      entry("sky_entity_shadows_enabled", "entitytinioff.jpg", "entitytinion.jpg"),
      entry("fog_enabled", "fogoff.jpg", "fogon.jpg"),
      entry("sky_cloud_shadows_enabled", "hmaritinioff.jpg", "hmaritinion.jpg"),
      entry("gpu_shadows_enabled", "realisticshadowsoff.jpg", "realisticshadowson.jpg"),
      entry("sky_godrays_enabled", "skygodraysoff.jpg", "skygodrayson.jpg"),
      entry("sky_light_colors_enabled", "svitloskyoff.jpg", "svitloskyon.jpg"),
      entry("sky_light_enabled", "tiniskyoff.jpg", "tiniskyon.jpg"),
      entry("tonemap_enabled", "tonemapoff.jpg", "tonemapon.jpg"),
      entry("sky_volumetric_god_rays_enabled", "volumetricskyoff.jpg", "volumetricskyon.jpg"),
      entry("water_enabled", "wateroff.jpg", "wateron.jpg"),
      entry("water_ssr_enabled", "waterssroff.jpg", "waterssron.jpg")
   );

   private ConfigPreviewImages() {
   }

   static boolean hasPreview(ConfigOption<?> option) {
      return IMAGES.containsKey(option.id());
   }

   static ResourceLocation image(ConfigOption<?> option, boolean after) {
      ConfigPreviewImages.ImagePair images = IMAGES.get(option.id());
      if (images == null) {
         throw new IllegalArgumentException("No preview images registered for option: " + option.id());
      } else {
         return after ? images.on() : images.off();
      }
   }

   private static Entry<String, ConfigPreviewImages.ImagePair> entry(String optionId, String offFile, String onFile) {
      return Map.entry(optionId, new ConfigPreviewImages.ImagePair(resource(offFile), resource(onFile)));
   }

   private static ResourceLocation resource(String fileName) {
      return ResourceLocation.fromNamespaceAndPath("luxium", "configpic/" + fileName);
   }

   private static record ImagePair(ResourceLocation off, ResourceLocation on) {
   }
}
