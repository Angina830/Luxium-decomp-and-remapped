package com.vinlanx.luxium.client.reflections;

import com.mojang.blaze3d.platform.NativeImage;
import com.vinlanx.luxium.client.ReflectionSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class RainPuddleManager {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final RainPuddleManager INSTANCE = new RainPuddleManager();
   private static final String RAIN_RESOURCE_ROOT = "rain";
   private static final String PNG_SUFFIX = ".png";
   private static final Path DEV_RAIN_DIR = Path.of("src", "main", "resources", "assets", "luxium", "rain");
   private static final int MIN_PUDDLE_SIZE = 1;
   private static final int MAX_PUDDLE_SIZE = 10;
   private final Map<ResourceLocation, RainHeightmap> heightmapsById = new LinkedHashMap<>();
   private List<RainHeightmap> orderedHeightmaps = List.of();
   private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<RainPuddleSurface>> puddlesByDimension = new HashMap<>();

   private RainPuddleManager() {
   }

   public static RainPuddleManager get() {
      return INSTANCE;
   }

   public synchronized int reloadHeightmaps(ResourceManager resourceManager) {
      LinkedHashMap<ResourceLocation, RainHeightmap> loaded = new LinkedHashMap<>();
      this.loadFromResourceManager(resourceManager, loaded);
      this.loadFromDevDirectory(loaded);
      this.heightmapsById.clear();
      this.heightmapsById.putAll(loaded);
      this.orderedHeightmaps = List.copyOf(this.heightmapsById.values());
      return this.orderedHeightmaps.size();
   }

   @Nullable
   public synchronized RainPuddleSurface placeRandomPuddle(Minecraft mc, BlockPos pos, int sizeBlocks) {
      ClientLevel level = mc.level;
      if (level == null) {
         return null;
      } else {
         this.reloadHeightmaps(mc.getResourceManager());
         if (this.orderedHeightmaps.isEmpty()) {
            return null;
         } else {
            int clampedSize = Math.max(1, Math.min(10, sizeBlocks));
            RainHeightmap selected = this.orderedHeightmaps.get(ThreadLocalRandom.current().nextInt(this.orderedHeightmaps.size()));
            RainPuddleSurface surface = this.buildSurface(mc, level, pos, selected, clampedSize);
            if (surface == null) {
               return null;
            } else {
               Long2ObjectOpenHashMap<RainPuddleSurface> puddles = this.puddlesFor(level);

               for (long coveredKey : surface.getCoveredBlockKeys()) {
                  this.removePlacementAt(puddles, coveredKey);
               }

               for (long coveredKey : surface.getCoveredBlockKeys()) {
                  puddles.put(coveredKey, surface);
               }

               ReflectionSystem.get().forceImmediateUpdate();
               return surface;
            }
         }
      }
   }

   @Nullable
   public synchronized RainPuddleSurface getActiveSurface(@Nullable ClientLevel level, BlockPos pos) {
      if (level == null) {
         return null;
      } else {
         Long2ObjectOpenHashMap<RainPuddleSurface> puddles = this.puddlesByDimension.get(level.dimension());
         return puddles == null ? null : (RainPuddleSurface)puddles.get(pos.asLong());
      }
   }

   public synchronized void removeIfUnsupported(@Nullable ClientLevel level, BlockPos pos, BlockState state) {
      if (level != null) {
         ReflectionMaterial material = ReflectionMaterialRegistry.find(state);
         boolean supportsTopFace = material != null && material.shouldReflectFace(Minecraft.getInstance(), pos, state, Direction.UP);
         if (!supportsTopFace) {
            Long2ObjectOpenHashMap<RainPuddleSurface> puddles = this.puddlesByDimension.get(level.dimension());
            if (puddles != null) {
               this.removePlacementAt(puddles, pos.asLong());
               if (puddles.isEmpty()) {
                  this.puddlesByDimension.remove(level.dimension());
               }
            }
         }
      }
   }

   public synchronized int getLoadedHeightmapCount() {
      return this.orderedHeightmaps.size();
   }

   private Long2ObjectOpenHashMap<RainPuddleSurface> puddlesFor(ClientLevel level) {
      return this.puddlesByDimension.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap());
   }

   @Nullable
   private RainPuddleSurface buildSurface(Minecraft mc, ClientLevel level, BlockPos center, RainHeightmap heightmap, int sizeBlocks) {
      int minX = center.getX() - (sizeBlocks - 1) / 2;
      int minZ = center.getZ() - (sizeBlocks - 1) / 2;
      LongArrayList covered = new LongArrayList(sizeBlocks * sizeBlocks);

      for (int dz = 0; dz < sizeBlocks; dz++) {
         for (int dx = 0; dx < sizeBlocks; dx++) {
            BlockPos candidate = new BlockPos(minX + dx, center.getY(), minZ + dz);
            BlockState candidateState = level.getBlockState(candidate);
            ReflectionMaterial material = ReflectionMaterialRegistry.find(candidateState);
            if (material != null && material.shouldReflectFace(mc, candidate, candidateState, Direction.UP)) {
               covered.add(candidate.asLong());
            }
         }
      }

      return covered.isEmpty() ? null : new RainPuddleSurface(heightmap, sizeBlocks, minX, minZ, covered.toLongArray());
   }

   private void removePlacementAt(Long2ObjectOpenHashMap<RainPuddleSurface> puddles, long blockKey) {
      RainPuddleSurface existing = (RainPuddleSurface)puddles.get(blockKey);
      if (existing != null) {
         for (long coveredKey : existing.getCoveredBlockKeys()) {
            puddles.remove(coveredKey);
         }
      }
   }

   private void loadFromResourceManager(ResourceManager resourceManager, Map<ResourceLocation, RainHeightmap> loaded) {
      Map<ResourceLocation, Resource> resources = resourceManager.listResources(
         "rain", location -> location.getNamespace().equals("luxium") && location.getPath().toLowerCase(Locale.ROOT).endsWith(".png")
      );
      List<ResourceLocation> ids = new ArrayList<>(resources.keySet());
      ids.sort(Comparator.comparing(ResourceLocation::getPath));

      for (ResourceLocation id : ids) {
         Resource resource = resources.get(id);
         if (resource != null) {
            try (InputStream input = resource.open()) {
               RainHeightmap heightmap = this.loadHeightmap(id, input);
               if (heightmap != null) {
                  loaded.put(id, heightmap);
               }
            } catch (IOException var13) {
               LOGGER.error("Failed to load rain heightmap {}", id, var13);
            }
         }
      }
   }

   private void loadFromDevDirectory(Map<ResourceLocation, RainHeightmap> loaded) {
      if (Files.isDirectory(DEV_RAIN_DIR)) {
         try (Stream<Path> stream = Files.walk(DEV_RAIN_DIR)) {
            for (Path file : stream.filter(x$0 -> Files.isRegularFile(x$0))
               .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
               .sorted(Comparator.comparing(path -> DEV_RAIN_DIR.relativize(path).toString()))
               .toList()) {
               String relativePath = DEV_RAIN_DIR.relativize(file).toString().replace('\\', '/');
               ResourceLocation id = ResourceLocation.fromNamespaceAndPath("luxium", "rain/" + relativePath);

               try (InputStream input = Files.newInputStream(file)) {
                  RainHeightmap heightmap = this.loadHeightmap(id, input);
                  if (heightmap != null) {
                     loaded.put(id, heightmap);
                  }
               } catch (IOException var14) {
                  LOGGER.error("Failed to load dev rain heightmap {}", file, var14);
               }
            }
         } catch (IOException var16) {
            LOGGER.error("Failed to scan {} for rain heightmaps", DEV_RAIN_DIR, var16);
         }
      }
   }

   @Nullable
   private RainHeightmap loadHeightmap(ResourceLocation id, InputStream input) {
      try {
         NativeImage image = NativeImage.read(input);

         RainHeightmap var18;
         label78: {
            label79: {
               Object opacity;
               try {
                  int width = image.getWidth();
                  int height = image.getHeight();
                  if (width > 0 && height > 0) {
                     float[] opacityx = new float[width * height];

                     for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                           int pixel = image.getPixelRGBA(x, y);
                           int alpha = pixel >>> 24 & 0xFF;
                           int luminance = pixel & 0xFF;
                           opacityx[y * width + x] = (float)alpha / 255.0F * ((float)luminance / 255.0F);
                        }
                     }

                     RainHeightmap heightmap = new RainHeightmap(id, width, height, opacityx);
                     if (!heightmap.hasVisiblePixels()) {
                        LOGGER.warn("Skipping rain heightmap {} because it has no visible puddle data", id);
                        var18 = null;
                        break label78;
                     }

                     var18 = heightmap;
                     break label79;
                  }

                  opacity = null;
               } catch (Throwable var13) {
                  if (image != null) {
                     try {
                        image.close();
                     } catch (Throwable var12) {
                        var13.addSuppressed(var12);
                     }
                  }

                  throw var13;
               }

               if (image != null) {
                  image.close();
               }

               return (RainHeightmap)opacity;
            }

            if (image != null) {
               image.close();
            }

            return var18;
         }

         if (image != null) {
            image.close();
         }

         return var18;
      } catch (IOException var14) {
         LOGGER.error("Failed to decode rain heightmap {}", id, var14);
         return null;
      }
   }
}
