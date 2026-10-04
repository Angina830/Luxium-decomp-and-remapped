package com.vinlanx.luxium.Testing.ExplosionTesting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

public final class ExplosionWorld {
   private static final ExplosionWorld INSTANCE = new ExplosionWorld();
   private final ExplosionFluidGrid fluid = new ExplosionFluidGrid();
   private final ExplosionParticleSystem particles = new ExplosionParticleSystem();
   private final ExplosionCollisionCache collision = new ExplosionCollisionCache();
   private final List<ExplosionWorld.Source> sources = new ArrayList<>();
   private final ConcurrentLinkedQueue<ExplosionWorld.SpawnRequest> spawnQueue = new ConcurrentLinkedQueue<>();
   private final ExecutorService simulationExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "Luxium-ExplosionSimulation");
      thread.setDaemon(true);
      thread.setPriority(5);
      return thread;
   });
   private final AtomicBoolean simulationQueued = new AtomicBoolean();
   private final AtomicLong pendingStepNanos = new AtomicLong();
   private final AtomicReference<ExplosionRenderSnapshot> publishedSnapshot = new AtomicReference<>(new ExplosionRenderSnapshot());
   private ExplosionRenderSnapshot writeSnapshot = new ExplosionRenderSnapshot();
   private ClientLevel level;
   private float simulationTime;
   private float fluidAccumulator;

   private ExplosionWorld() {
   }

   public static ExplosionWorld get() {
      return INSTANCE;
   }

   public void trigger(Vec3 center, int power) {
      ClientLevel currentLevel = Minecraft.getInstance().level;
      if (currentLevel != null) {
         ExplosionProfile profile = ExplosionProfile.fromPower(power);
         Vec3 actual = new Vec3(center.x, Math.max(center.y, (double)currentLevel.getMinBuildHeight() + 0.72), center.z);
         this.collision.rebuild(currentLevel, actual);
         this.spawnQueue.add(new ExplosionWorld.SpawnRequest(currentLevel, actual, profile));
         this.requestStep(0.0F);
      }
   }

   private void clearInternal() {
      this.fluid.reset();
      this.particles.clear();
      this.sources.clear();
      this.simulationTime = 0.0F;
      this.fluidAccumulator = 0.0F;
   }

   public void requestStep(float dt) {
      float safeDt = Math.max(0.0F, Math.min(dt, 0.05F));
      this.pendingStepNanos.addAndGet((long)(safeDt * 1.0E9F));
      this.scheduleSimulation();
   }

   ExplosionRenderSnapshot snapshot() {
      return this.publishedSnapshot.get();
   }

   private void stepInternal(float dt) {
      ExplosionWorld.SpawnRequest request;
      while ((request = this.spawnQueue.poll()) != null) {
         if (this.level != request.level) {
            this.clearInternal();
            this.level = request.level;
         }

         this.fluid.inject(this.collision, request.center, request.profile);
         this.particles.trigger(request.center, request.profile, true);
         this.sources.add(new ExplosionWorld.Source(request.center, request.profile));
         int limit = request.profile.tntKg() >= 100.0 ? 2 : 4;

         while (this.sources.size() > limit) {
            this.sources.remove(0);
         }
      }

      if (this.level != null && !(dt <= 0.0F)) {
         this.simulationTime += dt;
         Iterator<ExplosionWorld.Source> iterator = this.sources.iterator();

         while (iterator.hasNext()) {
            ExplosionWorld.Source source = iterator.next();
            source.age += dt;
            float duration = source.profile.durationScale();
            if (source.age < 1.05F * duration) {
               this.fluid.injectAfterburn(source.center, source.profile, source.age);
               double decay = Math.exp((double)(-source.age * (1.7F / duration)));
               source.emissionAccumulator = source.emissionAccumulator + (double)(dt * 1450.0F * source.profile.afterburnMultiplier()) * decay;
               int spawn = (int)source.emissionAccumulator;
               if (spawn > 0) {
                  source.emissionAccumulator -= (double)spawn;
                  this.particles.spawnAfterburn(source.center, source.profile, spawn);
               }
            }

            if (source.age >= 1.2F * duration) {
               iterator.remove();
            }
         }

         this.fluidAccumulator += dt;

         for (int iterations = 0; this.fluidAccumulator >= 0.033333335F && iterations < 2; iterations++) {
            this.fluid.step(0.033333335F);
            this.fluidAccumulator -= 0.033333335F;
         }

         this.particles.step(dt, this.fluid, this.collision, this.simulationTime);
      }
   }

   private void scheduleSimulation() {
      if (this.simulationQueued.compareAndSet(false, true)) {
         this.simulationExecutor.execute(() -> {
            try {
               while (true) {
                  long nanos = this.pendingStepNanos.getAndSet(0L);
                  this.stepInternal(Math.min((float)nanos / 1.0E9F, 0.05F));
                  this.publishSnapshot();
                  if (this.pendingStepNanos.get() > 0L || !this.spawnQueue.isEmpty()) {
                     continue;
                  }
               }
            } finally {
               this.simulationQueued.set(false);
               if (this.pendingStepNanos.get() > 0L || !this.spawnQueue.isEmpty()) {
                  this.scheduleSimulation();
               }
            }
         });
      }
   }

   private void publishSnapshot() {
      synchronized (this.writeSnapshot) {
         this.particles.writeSnapshot(this.writeSnapshot);
         ExplosionRenderSnapshot previousRead = this.publishedSnapshot.getAndSet(this.writeSnapshot);
         this.writeSnapshot = previousRead;
      }
   }

   private static final class Source {
      final Vec3 center;
      final ExplosionProfile profile;
      float age;
      double emissionAccumulator;

      Source(Vec3 center, ExplosionProfile profile) {
         this.center = center;
         this.profile = profile;
      }
   }

   private static record SpawnRequest(ClientLevel level, Vec3 center, ExplosionProfile profile) {
   }
}
