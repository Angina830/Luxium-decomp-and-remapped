package com.vinlanx.luxium.rtx.soasnottointerfere;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

public interface SectionCaptureAccess {
   void luxium$setSectionOrigin(@Nullable BlockPos var1);

   @Nullable
   BlockPos luxium$getSectionOrigin();
}
