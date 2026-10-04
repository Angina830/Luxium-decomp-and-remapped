package com.vinlanx.luxium.rtx.neogpuvanilla;

import com.vinlanx.luxium.Config;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32C;

public final class NeoGpuVanillaGpuDebug {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final int GL_TIMESTAMP = 36392;
   private static final int GL_QUERY_RESULT = 34918;
   private static final int GL_QUERY_RESULT_AVAILABLE = 34919;
   private static final int MAX_PENDING_QUERIES = 64;
   private static final Deque<NeoGpuVanillaGpuDebug.PendingQuery> PENDING = new ArrayDeque<>();
   private static boolean supportChecked;
   private static boolean timerSupported;
   private static int activeStartQuery;
   private static NeoGpuVanillaGpuDebug.Kind activeKind;
   private static long windowStartNs;
   private static long captureGpuNs;
   private static long fineBakeGpuNs;
   private static int capturedFaces;
   private static int fineBakeCount;
   private static int latestDirtyFaces;
   private static int latestSources;
   private static int latestPixelsPerBlock;

   private NeoGpuVanillaGpuDebug() {
   }

   public static void beginCapture() {
      begin(NeoGpuVanillaGpuDebug.Kind.CAPTURE);
   }

   public static void endCapture() {
      end(NeoGpuVanillaGpuDebug.Kind.CAPTURE);
   }

   public static void beginFineBake() {
      begin(NeoGpuVanillaGpuDebug.Kind.FINE_BAKE);
   }

   public static void endFineBake() {
      end(NeoGpuVanillaGpuDebug.Kind.FINE_BAKE);
      if (enabled()) {
         fineBakeCount++;
      }
   }

   public static void recordCapturedFace() {
      if (enabled()) {
         capturedFaces++;
      }
   }

   public static void tick(int sources, int dirtyFaces, int pixelsPerBlock) {
      if (enabled()) {
         latestSources = Math.max(0, sources);
         latestDirtyFaces = Math.max(0, dirtyFaces);
         latestPixelsPerBlock = Math.max(1, pixelsPerBlock);
         pollResults();
         long now = System.nanoTime();
         if (windowStartNs == 0L) {
            windowStartNs = now;
         } else if (now - windowStartNs >= 1000000000L) {
            String captureMs = String.format(Locale.ROOT, "%.2f", (double)captureGpuNs / 1000000.0);
            String fineBakeMs = String.format(Locale.ROOT, "%.2f", (double)fineBakeGpuNs / 1000000.0);
            LOGGER.info(
               "[NeoGpuVanilla GPU] capture={}ms | fineBake={}ms | dirtyFaces={} | capturedFaces={} | sources={} | bakes={} | ppb={} | pendingQueries={}",
               captureMs,
               fineBakeMs,
               latestDirtyFaces,
               capturedFaces,
               latestSources,
               fineBakeCount,
               latestPixelsPerBlock,
               PENDING.size()
            );
            captureGpuNs = 0L;
            fineBakeGpuNs = 0L;
            capturedFaces = 0;
            fineBakeCount = 0;
            windowStartNs = now;
         }
      } else {
         if (activeStartQuery != 0 || !PENDING.isEmpty() || windowStartNs != 0L) {
            reset();
         }
      }
   }

   public static void reset() {
      if (activeStartQuery != 0) {
         deleteQuery(activeStartQuery);
         activeStartQuery = 0;
         activeKind = null;
      }

      while (!PENDING.isEmpty()) {
         NeoGpuVanillaGpuDebug.PendingQuery query = PENDING.removeFirst();
         deleteQuery(query.startId());
         deleteQuery(query.endId());
      }

      windowStartNs = 0L;
      captureGpuNs = 0L;
      fineBakeGpuNs = 0L;
      capturedFaces = 0;
      fineBakeCount = 0;
      latestDirtyFaces = 0;
      latestSources = 0;
      latestPixelsPerBlock = 1;
   }

   private static void begin(NeoGpuVanillaGpuDebug.Kind kind) {
      if (enabled() && timerSupported() && activeStartQuery == 0 && PENDING.size() < 64) {
         int query = GL32C.glGenQueries();
         if (query != 0) {
            ARBTimerQuery.glQueryCounter(query, 36392);
            activeStartQuery = query;
            activeKind = kind;
         }
      }
   }

   private static boolean end(NeoGpuVanillaGpuDebug.Kind expected) {
      if (activeStartQuery != 0 && activeKind == expected) {
         int endQuery = GL32C.glGenQueries();
         if (endQuery == 0) {
            deleteQuery(activeStartQuery);
            activeStartQuery = 0;
            activeKind = null;
            return false;
         } else {
            ARBTimerQuery.glQueryCounter(endQuery, 36392);
            PENDING.addLast(new NeoGpuVanillaGpuDebug.PendingQuery(activeStartQuery, endQuery, activeKind));
            activeStartQuery = 0;
            activeKind = null;
            return true;
         }
      } else {
         return false;
      }
   }

   private static void pollResults() {
      if (timerSupported()) {
         int checks = PENDING.size();

         for (int i = 0; i < checks; i++) {
            NeoGpuVanillaGpuDebug.PendingQuery query = PENDING.peekFirst();
            if (query == null || GL32C.glGetQueryObjecti(query.endId(), 34919) == 0) {
               break;
            }

            PENDING.removeFirst();
            long startNs = ARBTimerQuery.glGetQueryObjecti64(query.startId(), 34918);
            long endNs = ARBTimerQuery.glGetQueryObjecti64(query.endId(), 34918);
            deleteQuery(query.startId());
            deleteQuery(query.endId());
            long elapsedNs = Math.max(0L, endNs - startNs);
            if (query.kind() == NeoGpuVanillaGpuDebug.Kind.CAPTURE) {
               captureGpuNs += elapsedNs;
            } else {
               fineBakeGpuNs += elapsedNs;
            }
         }
      }
   }

   private static void deleteQuery(int query) {
      if (query != 0) {
         try {
            GL32C.glDeleteQueries(query);
         } catch (Throwable var2) {
         }
      }
   }

   private static boolean timerSupported() {
      if (!supportChecked) {
         supportChecked = true;

         try {
            timerSupported = GL.getCapabilities().GL_ARB_timer_query;
         } catch (Throwable var1) {
            timerSupported = false;
         }

         if (enabled() && !timerSupported) {
            LOGGER.warn("[NeoGpuVanilla GPU] GL_ARB_timer_query is unavailable; GPU millisecond fields will stay at 0 while counters still log.");
         }
      }

      return timerSupported;
   }

   private static boolean enabled() {
      try {
         return Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaDebug) && NeoGpuVanilla.isConfiguredEnabled();
      } catch (Throwable var1) {
         return false;
      }
   }

   private static enum Kind {
      CAPTURE,
      FINE_BAKE;
   }

   private static record PendingQuery(int startId, int endId, NeoGpuVanillaGpuDebug.Kind kind) {
   }
}
