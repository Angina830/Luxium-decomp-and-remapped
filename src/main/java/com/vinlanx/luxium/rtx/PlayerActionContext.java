package com.vinlanx.luxium.rtx;

public final class PlayerActionContext {
   private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

   private PlayerActionContext() {
   }

   public static void begin() {
      DEPTH.set(DEPTH.get() + 1);
   }

   public static void end() {
      int depth = DEPTH.get();
      if (depth <= 1) {
         DEPTH.remove();
      } else {
         DEPTH.set(depth - 1);
      }
   }

   public static boolean isActive() {
      return DEPTH.get() > 0;
   }
}
