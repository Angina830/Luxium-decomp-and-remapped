package com.vinlanx.luxium.client.BlockLightTest;

import com.vinlanx.luxium.Config;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL20;

public final class BlockLightTest {
   private static final int LIMIT = 12;
   private static final Map<Long, BlockLightTest.Emitter> SOURCES = new HashMap<>();
   private static final Map<Long, Set<Long>> CHUNKS = new HashMap<>();
   private static final Set<Long> SCANNED_CHUNKS = new HashSet<>();
   private static final Map<Integer, int[]> PROGRAMS = new HashMap<>();
   private static final BlockLightTest.Emitter[] NEAR = new BlockLightTest.Emitter[12];
   private static final float[] DISTANCES = new float[12];
   private static final float[] LOCATIONS = new float[48];
   private static final float[] COLORS = new float[48];
   private static ClientLevel level;
   private static int size;
   private static boolean previouslyEnabled;

   private BlockLightTest() {
   }

   public static boolean enabled() {
      return Config.isFeatureEnabled(Config.CLIENT.blockLightTestEnabled);
   }

   public static void clearProgramCache() {
      PROGRAMS.clear();
   }

   public static void onChunk(ClientLevel current, LevelChunk chunk) {
      if (enabled()) {
         setLevel(current);
         int x = chunk.getPos().x;
         int z = chunk.getPos().z;
         removeChunk(x, z);
         long chunkKey = ChunkPos.asLong(x, z);
         SCANNED_CHUNKS.add(chunkKey);
         Set<Long> members = new HashSet<>();
         chunk.findBlockLightSources((pos, state) -> {
            int strength = state.getLightEmission(current, pos);
            if (strength > 0) {
               long key = pos.asLong();
               SOURCES.put(key, create(pos, state, strength));
               members.add(key);
            }
         });
         CHUNKS.put(chunkKey, members);
      }
   }

   public static void removeChunk(int x, int z) {
      SCANNED_CHUNKS.remove(ChunkPos.asLong(x, z));
      Set<Long> members = CHUNKS.remove(ChunkPos.asLong(x, z));
      if (members != null) {
         for (long key : members) {
            SOURCES.remove(key);
         }
      }
   }

   public static void changed(ClientLevel current, BlockPos pos, BlockState state) {
      if (enabled()) {
         setLevel(current);
         long key = pos.asLong();
         int strength = state.getLightEmission(current, pos);
         Set<Long> members = CHUNKS.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new HashSet<>());
         if (strength <= 0) {
            SOURCES.remove(key);
            members.remove(key);
         } else {
            SOURCES.put(key, create(pos, state, strength));
            members.add(key);
         }
      }
   }

   public static void tick(ClientLevel current) {
      if (!enabled()) {
         if (previouslyEnabled) {
            setLevel(null);
            PROGRAMS.clear();
         }

         previouslyEnabled = false;
      } else {
         boolean firstTick = !previouslyEnabled || level != current;
         previouslyEnabled = true;
         setLevel(current);
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player != null) {
            if (firstTick) {
               PROGRAMS.clear();
            }

            int centerX = minecraft.player.blockPosition().getX() >> 4;
            int centerZ = minecraft.player.blockPosition().getZ() >> 4;

            for (int radius = 0; radius <= 3; radius++) {
               boolean scanned = false;

               for (int dz = -radius; dz <= radius && !scanned; dz++) {
                  for (int dx = -radius; dx <= radius; dx++) {
                     int x = centerX + dx;
                     int z = centerZ + dz;
                     if (!SCANNED_CHUNKS.contains(ChunkPos.asLong(x, z))) {
                        LevelChunk chunk = current.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                        if (chunk != null) {
                           onChunk(current, chunk);
                           scanned = true;
                           break;
                        }
                     }
                  }
               }

               if (scanned) {
                  break;
               }
            }

            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            Arrays.fill(NEAR, null);
            Arrays.fill(DISTANCES, Float.POSITIVE_INFINITY);
            size = 0;

            for (BlockLightTest.Emitter source : SOURCES.values()) {
               addNearby(source, camera);
            }

            if (minecraft.player.getMainHandItem().getItem() instanceof BlockItem item) {
               BlockState held = item.getBlock().defaultBlockState();
               int light = held.getLightEmission(current, minecraft.player.blockPosition());
               if (light > 0) {
                  addNearby(new BlockLightTest.Emitter(camera.x, camera.y - 0.15, camera.z, light, tint(item.getBlock().getDescriptionId()), true), camera);
               }
            }
         }
      }
   }

   public static void bind(int program, boolean entityProgram) {
      if (program > 0) {
         int[] slots = PROGRAMS.computeIfAbsent(
            program,
            id -> new int[]{
                  GL20.glGetUniformLocation(id, entityProgram ? "BtCount" : "u_BtCount"),
                  GL20.glGetUniformLocation(id, entityProgram ? "BtEmitters[0]" : "u_BtEmitters[0]"),
                  GL20.glGetUniformLocation(id, entityProgram ? "BtColors[0]" : "u_BtColors[0]"),
                  entityProgram ? GL20.glGetUniformLocation(id, "BtActive") : -1
               }
         );
         if (slots[3] >= 0) {
            GL20.glUniform1i(slots[3], enabled() ? 1 : 0);
         }

         if (enabled()) {
            if (slots[0] >= 0) {
               Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
               Arrays.fill(LOCATIONS, 0.0F);
               Arrays.fill(COLORS, 0.0F);

               for (int i = 0; i < size; i++) {
                  BlockLightTest.Emitter light = NEAR[i];
                  int offset = i * 4;
                  LOCATIONS[offset] = (float)(light.x - camera.x);
                  LOCATIONS[offset + 1] = (float)(light.y - camera.y);
                  LOCATIONS[offset + 2] = (float)(light.z - camera.z);
                  LOCATIONS[offset + 3] = (light.held ? -1.0F : 1.0F) * (float)light.level / 15.0F;
                  COLORS[offset] = light.rgb[0];
                  COLORS[offset + 1] = light.rgb[1];
                  COLORS[offset + 2] = light.rgb[2];
               }

               GL20.glUniform1i(slots[0], size);
               if (slots[1] >= 0) {
                  GL20.glUniform4fv(slots[1], LOCATIONS);
               }

               if (slots[2] >= 0) {
                  GL20.glUniform4fv(slots[2], COLORS);
               }
            }
         }
      }
   }

   private static void addNearby(BlockLightTest.Emitter source, Vec3 camera) {
      float dx = (float)(source.x - camera.x);
      float dy = (float)(source.y - camera.y);
      float dz = (float)(source.z - camera.z);
      float distance = dx * dx + dy * dy + dz * dz;
      if (!(distance > 1296.0F)) {
         int insert = 0;

         while (insert < size && distance >= DISTANCES[insert]) {
            insert++;
         }

         if (insert < 12) {
            int end = Math.min(size, 11);

            for (int index = end; index > insert; index--) {
               NEAR[index] = NEAR[index - 1];
               DISTANCES[index] = DISTANCES[index - 1];
            }

            NEAR[insert] = source;
            DISTANCES[insert] = distance;
            size = Math.min(size + 1, 12);
         }
      }
   }

   private static void setLevel(ClientLevel next) {
      if (level != next) {
         level = next;
         SOURCES.clear();
         CHUNKS.clear();
         SCANNED_CHUNKS.clear();
         size = 0;
      }
   }

   private static BlockLightTest.Emitter create(BlockPos pos, BlockState state, int strength) {
      ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
      return new BlockLightTest.Emitter(
         (double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, Math.min(strength, 15), tint(id == null ? "" : id.getPath()), false
      );
   }

   private static float[] tint(String name) {
      if (name.contains("soul")) {
         return new float[]{0.28F, 0.78F, 1.0F};
      } else if (name.contains("redstone")) {
         return new float[]{1.0F, 0.2F, 0.08F};
      } else if (name.contains("sea_lantern") || name.contains("conduit")) {
         return new float[]{0.58F, 0.95F, 1.0F};
      } else if (name.contains("end_rod") || name.contains("pearlescent_froglight")) {
         return new float[]{1.0F, 0.72F, 0.95F};
      } else if (name.contains("verdant_froglight")) {
         return new float[]{0.68F, 1.0F, 0.66F};
      } else if (name.contains("glowstone") || name.contains("ochre_froglight") || name.contains("shroomlight")) {
         return new float[]{1.0F, 0.82F, 0.48F};
      } else if (name.contains("sculk") || name.contains("respawn_anchor")) {
         return new float[]{0.2F, 0.78F, 0.92F};
      } else if (name.contains("amethyst")) {
         return new float[]{0.78F, 0.48F, 1.0F};
      } else if (name.contains("glow_lichen") || name.contains("glow_berries")) {
         return new float[]{0.72F, 1.0F, 0.58F};
      } else {
         return !name.contains("lava") && !name.contains("torch") && !name.contains("lantern") && !name.contains("fire") && !name.contains("candle")
            ? new float[]{1.0F, 0.76F, 0.48F}
            : new float[]{1.0F, 0.58F, 0.26F};
      }
   }

   private static record Emitter(double x, double y, double z, int level, float[] rgb, boolean held) {
   }
}
