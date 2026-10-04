package com.vinlanx.luxium.client.plantswave;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class PlantWaveRegistry {
   private static final Set<Block> LEAVES = Set.of(
      Blocks.OAK_LEAVES,
      Blocks.SPRUCE_LEAVES,
      Blocks.BIRCH_LEAVES,
      Blocks.JUNGLE_LEAVES,
      Blocks.ACACIA_LEAVES,
      Blocks.CHERRY_LEAVES,
      Blocks.DARK_OAK_LEAVES,
      Blocks.MANGROVE_LEAVES,
      Blocks.AZALEA_LEAVES,
      Blocks.FLOWERING_AZALEA_LEAVES
   );
   private static final Set<Block> ROOTED = Set.of(
      Blocks.OAK_SAPLING,
      Blocks.SPRUCE_SAPLING,
      Blocks.BIRCH_SAPLING,
      Blocks.JUNGLE_SAPLING,
      Blocks.ACACIA_SAPLING,
      Blocks.CHERRY_SAPLING,
      Blocks.DARK_OAK_SAPLING,
      Blocks.GRASS,
      Blocks.FERN,
      Blocks.DEAD_BUSH,
      Blocks.DANDELION,
      Blocks.TORCHFLOWER,
      Blocks.POPPY,
      Blocks.BLUE_ORCHID,
      Blocks.ALLIUM,
      Blocks.AZURE_BLUET,
      Blocks.RED_TULIP,
      Blocks.ORANGE_TULIP,
      Blocks.WHITE_TULIP,
      Blocks.PINK_TULIP,
      Blocks.OXEYE_DAISY,
      Blocks.CORNFLOWER,
      Blocks.WITHER_ROSE,
      Blocks.LILY_OF_THE_VALLEY,
      Blocks.BROWN_MUSHROOM,
      Blocks.RED_MUSHROOM,
      Blocks.WHEAT,
      Blocks.CARROTS,
      Blocks.POTATOES,
      Blocks.BEETROOTS,
      Blocks.NETHER_WART,
      Blocks.PUMPKIN_STEM,
      Blocks.MELON_STEM,
      Blocks.ATTACHED_PUMPKIN_STEM,
      Blocks.ATTACHED_MELON_STEM,
      Blocks.TALL_GRASS,
      Blocks.LARGE_FERN,
      Blocks.SUNFLOWER,
      Blocks.LILAC,
      Blocks.ROSE_BUSH,
      Blocks.PEONY,
      Blocks.BAMBOO_SAPLING,
      Blocks.SWEET_BERRY_BUSH,
      Blocks.WARPED_FUNGUS,
      Blocks.CRIMSON_FUNGUS,
      Blocks.WARPED_ROOTS,
      Blocks.CRIMSON_ROOTS,
      Blocks.NETHER_SPROUTS,
      Blocks.AZALEA,
      Blocks.FLOWERING_AZALEA,
      Blocks.SMALL_DRIPLEAF,
      Blocks.PITCHER_CROP,
      Blocks.PITCHER_PLANT,
      Blocks.TORCHFLOWER_CROP
   );
   private static final Set<Block> AQUATIC = Set.of(Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.LILY_PAD);

   private PlantWaveRegistry() {
   }

   public static PlantWaveProfile resolve(BlockGetter level, BlockPos pos, BlockState state) {
      Block block = state.getBlock();
      if (LEAVES.contains(block)) {
         return PlantWaveProfile.LEAVES;
      } else if (block == Blocks.MANGROVE_PROPAGULE) {
         return state.getValue(MangrovePropaguleBlock.HANGING) ? PlantWaveProfile.HANGING : PlantWaveProfile.ROOTED;
      } else if (block == Blocks.HANGING_ROOTS || block == Blocks.SPORE_BLOSSOM) {
         return PlantWaveProfile.HANGING;
      } else if (isHangingChain(block)) {
         Block above = level.getBlockState(pos.above()).getBlock();
         return sameHangingFamily(block, above) ? PlantWaveProfile.FREE : PlantWaveProfile.HANGING;
      } else if (isUpwardChain(block)) {
         Block below = level.getBlockState(pos.below()).getBlock();
         return sameUpwardFamily(block, below) ? PlantWaveProfile.FREE : PlantWaveProfile.ROOTED;
      } else if (AQUATIC.contains(block)) {
         if (block != Blocks.KELP && block != Blocks.KELP_PLANT) {
            if (state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
               return PlantWaveProfile.AQUATIC_FREE;
            } else {
               return block == Blocks.LILY_PAD ? PlantWaveProfile.AQUATIC_FREE : PlantWaveProfile.AQUATIC_ROOTED;
            }
         } else {
            Block below = level.getBlockState(pos.below()).getBlock();
            return isKelp(below) ? PlantWaveProfile.AQUATIC_FREE : PlantWaveProfile.AQUATIC_ROOTED;
         }
      } else if (state.hasProperty(DoublePlantBlock.HALF)) {
         return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? PlantWaveProfile.FREE : PlantWaveProfile.ROOTED;
      } else {
         return ROOTED.contains(block) ? PlantWaveProfile.ROOTED : PlantWaveProfile.NONE;
      }
   }

   private static boolean isUpwardChain(Block block) {
      return block == Blocks.SUGAR_CANE
         || block == Blocks.BAMBOO
         || block == Blocks.TWISTING_VINES
         || block == Blocks.TWISTING_VINES_PLANT
         || block == Blocks.BIG_DRIPLEAF
         || block == Blocks.BIG_DRIPLEAF_STEM;
   }

   private static boolean sameUpwardFamily(Block first, Block second) {
      if (first == Blocks.SUGAR_CANE) {
         return second == Blocks.SUGAR_CANE;
      } else if (first == Blocks.BAMBOO) {
         return second == Blocks.BAMBOO || second == Blocks.BAMBOO_SAPLING;
      } else if (first != Blocks.TWISTING_VINES && first != Blocks.TWISTING_VINES_PLANT) {
         return first != Blocks.BIG_DRIPLEAF && first != Blocks.BIG_DRIPLEAF_STEM ? false : second == Blocks.BIG_DRIPLEAF || second == Blocks.BIG_DRIPLEAF_STEM;
      } else {
         return second == Blocks.TWISTING_VINES || second == Blocks.TWISTING_VINES_PLANT;
      }
   }

   private static boolean isHangingChain(Block block) {
      return block == Blocks.VINE
         || block == Blocks.WEEPING_VINES
         || block == Blocks.WEEPING_VINES_PLANT
         || block == Blocks.CAVE_VINES
         || block == Blocks.CAVE_VINES_PLANT;
   }

   private static boolean sameHangingFamily(Block first, Block second) {
      if (first == Blocks.VINE) {
         return second == Blocks.VINE;
      } else if (first != Blocks.WEEPING_VINES && first != Blocks.WEEPING_VINES_PLANT) {
         return first != Blocks.CAVE_VINES && first != Blocks.CAVE_VINES_PLANT ? false : second == Blocks.CAVE_VINES || second == Blocks.CAVE_VINES_PLANT;
      } else {
         return second == Blocks.WEEPING_VINES || second == Blocks.WEEPING_VINES_PLANT;
      }
   }

   private static boolean isKelp(Block block) {
      return block == Blocks.KELP || block == Blocks.KELP_PLANT;
   }
}
