package com.vinlanx.luxium.Testing.ExplosionTesting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class ExplosionParticleSystem {
   static final int MAX_PARTICLES = 70000;
   static final byte FIRE = 0;
   static final byte SMOKE = 1;
   static final byte SPARK = 2;
   static final byte DUST = 3;
   private static final float AMBIENT_K = 293.15F;
   private final Random random = new Random(7L);
   private final ArrayList<ExplosionParticleSystem.Particle> particles = new ArrayList<>(70000);

   void clear() {
      this.particles.clear();
   }

   int count() {
      return this.particles.size();
   }

   void writeSnapshot(ExplosionRenderSnapshot snapshot) {
      int smoke = 0;

      for (ExplosionParticleSystem.Particle particle : this.particles) {
         if (!particle.emissive()) {
            smoke++;
         }
      }

      int smokeIndex = 0;
      int hotIndex = smoke;

      for (ExplosionParticleSystem.Particle particlex : this.particles) {
         int index = particlex.emissive() ? hotIndex++ : smokeIndex++;
         snapshot.x[index] = particlex.x;
         snapshot.y[index] = particlex.y;
         snapshot.z[index] = particlex.z;
         snapshot.r[index] = particlex.r;
         snapshot.g[index] = particlex.g;
         snapshot.b[index] = particlex.b;
         snapshot.alpha[index] = particlex.alpha;
         snapshot.size[index] = particlex.size;
         snapshot.frame[index] = (byte)particlex.frame;
      }

      snapshot.smokeCount = smoke;
      snapshot.totalCount = this.particles.size();
   }

   void trigger(Vec3 center, ExplosionProfile profile, boolean nearGround) {
      int requested = (int)(12400.0F * profile.particleMultiplier());
      if (70000 - this.particles.size() < Math.max(2000, requested)) {
         this.compact(true);
      }

      int available = 70000 - this.particles.size();
      if (available > 0) {
         double multiplier = (double)profile.particleMultiplier();
         double[] target = new double[]{
            4300.0 * multiplier,
            5900.0 * multiplier,
            720.0 * Math.pow(multiplier, 0.84),
            nearGround ? 1450.0 * multiplier * (double)profile.dustMultiplier() : 0.0
         };
         double total = target[0] + target[1] + target[2] + target[3];
         if (total > (double)available) {
            for (int i = 0; i < target.length; i++) {
               target[i] *= (double)available / total;
            }
         }

         this.spawnKind(center, profile, (byte)0, (int)target[0], nearGround);
         this.spawnKind(center, profile, (byte)1, (int)target[1], nearGround);
         this.spawnKind(center, profile, (byte)2, (int)target[2], nearGround);
         this.spawnKind(center, profile, (byte)3, (int)target[3], true);
      }
   }

   void spawnAfterburn(Vec3 center, ExplosionProfile profile, int requested) {
      int n = Math.min(requested, 70000 - this.particles.size());

      for (int i = 0; i < n; i++) {
         byte kind = (byte)(this.random.nextFloat() < 0.55F ? 0 : 1);
         Vec3 direction = this.randomDirection(true);
         double radius = (double)(this.randomRange(0.08F, 0.48F) * profile.coreRadius() / 1.35F);
         Vec3 position = center.add(direction.scale(radius));
         double speed = (double)(this.randomRange(0.35F, 1.7F) * profile.blastStrength() * 0.22F);
         Vec3 velocity = direction.scale(speed).add(0.0, (double)this.randomRange(0.2F, 1.1F) * Math.pow((double)profile.scale(), 0.14), 0.0);
         float temp = (kind == 0 ? this.randomRange(1900.0F, 4300.0F) : this.randomRange(700.0F, 1900.0F)) * profile.heatMultiplier();
         float life = (kind == 0 ? this.randomRange(0.8F, 1.8F) : this.randomRange(3.6F, 7.0F)) * profile.durationScale();
         this.add(position, velocity, temp, this.randomRange(0.38F, 1.05F), life, this.randomRange(0.3F, 0.9F) * profile.particleSizeScale(), kind);
      }
   }

   private void spawnKind(Vec3 center, ExplosionProfile profile, byte kind, int count, boolean nearGround) {
      float spatialScale = profile.coreRadius() / 1.35F;

      for (int i = 0; i < count && this.particles.size() < 70000; i++) {
         boolean upward = nearGround || kind == 2 || kind == 3;
         Vec3 direction = kind == 3 ? this.dustDirection() : this.randomDirection(upward);
         float radius;
         float speed;
         float temperature;
         float density;
         float life;
         float size;
         if (kind == 0) {
            radius = (float)Math.cbrt((double)this.random.nextFloat()) * 0.58F * spatialScale;
            speed = profile.blastStrength() * this.randomRange(0.62F, 1.28F);
            temperature = this.randomRange(2600.0F, 6100.0F) * profile.heatMultiplier();
            density = this.randomRange(0.25F, 0.92F);
            life = this.randomRange(1.15F, 2.65F) * profile.durationScale();
            size = this.randomRange(0.36F, 1.08F) * profile.particleSizeScale();
         } else if (kind == 1) {
            radius = (float)Math.cbrt((double)this.random.nextFloat()) * 0.78F * spatialScale;
            speed = profile.blastStrength() * this.randomRange(0.28F, 0.72F);
            temperature = this.randomRange(850.0F, 2900.0F) * profile.heatMultiplier();
            density = this.randomRange(0.46F, 1.25F);
            life = this.randomRange(5.0F, 10.5F) * profile.durationScale();
            size = this.randomRange(0.54F, 1.44F) * profile.particleSizeScale();
         } else if (kind == 2) {
            radius = this.randomRange(0.1F, 0.5F) * spatialScale;
            speed = profile.blastStrength() * this.randomRange(1.15F, 2.4F);
            temperature = this.randomRange(1800.0F, 4300.0F) * profile.heatMultiplier();
            density = this.randomRange(0.5F, 1.0F);
            life = this.randomRange(0.8F, 2.1F) * (float)Math.pow((double)profile.durationScale(), 0.72);
            size = this.randomRange(0.15F, 0.36F) * profile.particleSizeScale();
         } else {
            radius = this.randomRange(0.15F, 0.75F) * spatialScale;
            speed = profile.blastStrength() * this.randomRange(0.55F, 1.15F);
            temperature = this.randomRange(450.0F, 1350.0F) * profile.heatMultiplier();
            density = this.randomRange(0.55F, 1.4F);
            life = this.randomRange(3.0F, 7.2F) * profile.durationScale();
            size = this.randomRange(0.36F, 1.02F) * profile.particleSizeScale();
         }

         Vec3 position = center.add(direction.scale((double)radius));
         if (kind == 3) {
            position = new Vec3(position.x, Math.max(position.y, center.y + 0.02), position.z);
         }

         Vec3 velocity = direction.scale((double)speed);
         if (kind == 0 || kind == 1) {
            velocity = velocity.add(0.0, (double)this.randomRange(0.25F, 1.8F) * Math.pow((double)profile.scale(), 0.17), 0.0);
         }

         this.add(position, velocity, temperature, density, life, size, kind);
      }
   }

   void step(float dt, ExplosionFluidGrid fluid, ExplosionCollisionCache collision, float simulationTime) {
      dt = Mth.clamp(dt, 0.0F, 0.04F);

      for (ExplosionParticleSystem.Particle p : this.particles) {
         p.age += dt;
         Vec3 gridVelocity = fluid.sampleVelocity((double)p.x, (double)p.y, (double)p.z);
         float coupling = p.kind == 1 ? 2.7F : (p.kind == 0 ? 1.85F : (p.kind == 3 ? 1.3F : 0.28F));
         float blend = 1.0F - (float)Math.exp((double)(-coupling * dt));
         p.vx = (float)((double)p.vx + (gridVelocity.x - (double)p.vx) * (double)blend);
         p.vy = (float)((double)p.vy + (gridVelocity.y - (double)p.vy) * (double)blend);
         p.vz = (float)((double)p.vz + (gridVelocity.z - (double)p.vz) * (double)blend);
         float qx = p.x * 0.72F + p.seed * 0.017F;
         float qy = p.y * 0.93F + p.seed * 0.011F;
         float qz = p.z * 0.72F + p.seed * 0.019F;
         float curlX = Mth.sin(qy * 1.7F + simulationTime * 1.1F) - Mth.cos(qz * 1.25F - simulationTime * 0.8F);
         float curlY = Mth.sin(qz * 1.55F + simulationTime * 0.7F) - Mth.cos(qx * 1.45F + simulationTime * 0.9F);
         float curlZ = Mth.sin(qx * 1.35F - simulationTime) - Mth.cos(qy * 1.65F - simulationTime * 0.6F);
         float turbulenceWeight = p.kind == 2 ? 0.12F : 0.62F;
         p.vx += curlX * dt * turbulenceWeight;
         p.vy += curlY * dt * turbulenceWeight;
         p.vz += curlZ * dt * turbulenceWeight;
         float hotness = Mth.clamp((p.temperature - 293.15F) / 2600.0F, 0.0F, 2.0F);
         p.vy = p.vy + (hotness * 4.1F - p.density * (p.kind == 3 ? 0.72F : 0.16F)) * dt;
         p.vy = p.vy - (p.kind == 2 ? 9.81F : (p.kind == 3 ? 2.2F : 0.08F)) * dt;
         float targetTemperature = 293.15F + fluid.sampleTemperature((double)p.x, (double)p.y, (double)p.z) * 1350.0F;
         p.temperature = p.temperature + Math.max(targetTemperature - p.temperature, 0.0F) * (1.0F - (float)Math.exp((double)(-dt * 0.8F)));
         float cooling = 0.46F * (p.kind == 2 ? 1.65F : (p.kind == 0 ? 0.82F : 1.0F));
         p.temperature = p.temperature + (293.15F - p.temperature) * (1.0F - (float)Math.exp((double)(-cooling * dt)));
         p.density = p.density * (float)Math.exp((double)(-dt * (p.kind == 1 ? 0.055F : (p.kind == 3 ? 0.1F : 0.18F))));
         if (p.kind == 0 && (p.temperature < 1150.0F || p.age > p.life * 0.68F)) {
            p.kind = 1;
            p.density = Math.max(p.density, 0.42F);
            p.life = Math.max(p.life, p.age + this.randomRange(2.5F, 5.0F));
         }

         float drag = p.kind == 2 ? 0.22F : (p.kind == 3 ? 0.55F : 0.34F);
         float dragFactor = (float)Math.exp((double)(-drag * dt));
         p.vx *= dragFactor;
         p.vy *= dragFactor;
         p.vz *= dragFactor;
         moveWithCollision(p, dt, collision);
         this.updateVisuals(p, hotness);
      }

      this.compact(false);
   }

   private static void moveWithCollision(ExplosionParticleSystem.Particle p, float dt, ExplosionCollisionCache collision) {
      float oldX = p.x;
      float oldY = p.y;
      float oldZ = p.z;
      float nextX = oldX + p.vx * dt;
      if (!collision.isSolid((double)nextX, (double)oldY, (double)oldZ)) {
         p.x = nextX;
      } else {
         p.vx *= -0.16F;
      }

      float nextY = oldY + p.vy * dt;
      if (!collision.isSolid((double)p.x, (double)nextY, (double)oldZ)) {
         p.y = nextY;
      } else {
         p.vy = p.vy * (p.kind == 2 ? -0.42F : -0.08F);
      }

      float nextZ = oldZ + p.vz * dt;
      if (!collision.isSolid((double)p.x, (double)p.y, (double)nextZ)) {
         p.z = nextZ;
      } else {
         p.vz *= -0.16F;
      }
   }

   private void updateVisuals(ExplosionParticleSystem.Particle p, float hotness) {
      float fadeIn = Mth.clamp(p.age / 0.08F, 0.0F, 1.0F);
      float fadeOut = Mth.clamp((p.life - p.age) / Math.max(p.life * 0.24F, 0.15F), 0.0F, 1.0F);
      float glow = Mth.clamp((p.temperature - 620.0F) / 1750.0F, 0.0F, 1.0F) * (p.kind == 1 ? 0.72F : 1.0F);
      float[] blackbody = blackbody(p.temperature);
      float soot = Mth.clamp(0.24F - p.density * 0.13F + hotness * 0.05F, 0.025F, 0.31F);
      float sr = soot * (p.kind == 3 ? 1.55F : 1.02F);
      float sg = soot * (p.kind == 3 ? 1.23F : 0.95F);
      float sb = soot * (p.kind == 3 ? 0.82F : 0.88F);
      p.r = Mth.clamp(Mth.lerp(glow, sr, blackbody[0]) + blackbody[0] * glow * glow * 0.32F, 0.0F, 2.4F);
      p.g = Mth.clamp(Mth.lerp(glow, sg, blackbody[1]) + blackbody[1] * glow * glow * 0.32F, 0.0F, 2.4F);
      p.b = Mth.clamp(Mth.lerp(glow, sb, blackbody[2]) + blackbody[2] * glow * glow * 0.32F, 0.0F, 2.4F);
      float kindAlpha = p.kind == 0 ? 0.68F : (p.kind == 2 ? 0.96F : (p.kind == 3 ? 0.42F : 0.31F));
      p.alpha = Mth.clamp(p.density * fadeIn * fadeOut * kindAlpha, 0.0F, 0.98F);
      float growth = p.kind == 1 ? 1.0F + p.age * 0.43F : (p.kind == 3 ? 1.0F + p.age * 0.22F : 1.0F + p.age * 0.12F);
      p.size = p.baseSize * growth * Mth.clamp(0.75F + p.density * 0.48F, 0.55F, 2.0F);
      float score = p.density * 3.3F + Mth.clamp(p.age / Math.max(p.life, 1.0E-4F), 0.0F, 1.0F) * 3.4F;
      score += p.kind == 1 ? 1.0F : (p.kind == 3 ? 0.6F : 0.0F);
      p.frame = Mth.clamp(Mth.floor(score), 0, 7);
   }

   private void compact(boolean forceDrop) {
      Iterator<ExplosionParticleSystem.Particle> it = this.particles.iterator();

      while (it.hasNext()) {
         ExplosionParticleSystem.Particle p = it.next();
         boolean alive = p.age < p.life && p.alpha > 0.003F && (double)p.y > -64.0;
         if (forceDrop && (double)this.particles.size() > 50400.0 && p.kind != 0 && p.kind != 2 && p.age >= p.life * 0.72F) {
            alive = false;
         }

         if (!alive) {
            it.remove();
         }
      }
   }

   private void add(Vec3 position, Vec3 velocity, float temperature, float density, float life, float size, byte kind) {
      ExplosionParticleSystem.Particle p = new ExplosionParticleSystem.Particle();
      p.x = (float)position.x;
      p.y = (float)position.y;
      p.z = (float)position.z;
      p.vx = (float)velocity.x;
      p.vy = (float)velocity.y;
      p.vz = (float)velocity.z;
      p.temperature = temperature;
      p.density = density;
      p.life = life;
      p.baseSize = size;
      p.size = size;
      p.kind = kind;
      p.seed = this.random.nextFloat() * 100.0F;
      p.alpha = 1.0F;
      this.particles.add(p);
   }

   private Vec3 randomDirection(boolean upward) {
      double x = this.random.nextGaussian();
      double y = this.random.nextGaussian();
      double z = this.random.nextGaussian();
      if (upward) {
         y = Math.abs(y) * 0.82 + 0.08;
      }

      return new Vec3(x, y, z).normalize();
   }

   private Vec3 dustDirection() {
      double angle = this.random.nextDouble() * Math.PI * 2.0;
      return new Vec3(Math.cos(angle), (double)this.randomRange(0.03F, 0.24F), Math.sin(angle)).normalize();
   }

   private float randomRange(float min, float max) {
      return min + this.random.nextFloat() * (max - min);
   }

   private static float[] blackbody(float kelvin) {
      float t = Mth.clamp(kelvin, 1000.0F, 40000.0F) / 100.0F;
      float safeHot = Math.max(t - 60.0F, 0.001F);
      float r = t <= 66.0F ? 255.0F : 329.69873F * (float)Math.pow((double)safeHot, -0.1332047592);
      float g = t <= 66.0F
         ? 99.4708F * (float)Math.log((double)Math.max(t, 1.0E-4F)) - 161.11957F
         : 288.12216F * (float)Math.pow((double)safeHot, -0.0755148492);
      float b = t >= 66.0F ? 255.0F : (t <= 19.0F ? 0.0F : 138.51773F * (float)Math.log((double)Math.max(t - 10.0F, 1.0E-4F)) - 305.0448F);
      return new float[]{Mth.clamp(r / 255.0F, 0.0F, 1.0F), Mth.clamp(g / 255.0F, 0.0F, 1.0F), Mth.clamp(b / 255.0F, 0.0F, 1.0F)};
   }

   static final class Particle {
      float x;
      float y;
      float z;
      float vx;
      float vy;
      float vz;
      float temperature;
      float density;
      float age;
      float life;
      float baseSize;
      float size;
      float seed;
      float r;
      float g;
      float b;
      float alpha;
      int frame;
      byte kind;

      boolean emissive() {
         return this.kind == 0 || this.kind == 2 || this.temperature > 1450.0F;
      }

      double distanceToSqr(Vec3 camera) {
         double dx = (double)this.x - camera.x;
         double dy = (double)this.y - camera.y;
         double dz = (double)this.z - camera.z;
         return dx * dx + dy * dy + dz * dz;
      }
   }
}
